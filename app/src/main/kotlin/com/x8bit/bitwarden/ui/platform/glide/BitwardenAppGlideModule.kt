package com.x8bit.bitwarden.ui.platform.glide

import android.content.Context
import com.bitwarden.network.ssl.CertificateProvider
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.ModelLoader
import com.bumptech.glide.load.model.ModelLoaderFactory
import com.bumptech.glide.load.model.MultiModelLoaderFactory
import com.bumptech.glide.module.AppGlideModule
import com.x8bit.bitwarden.data.platform.manager.CertificateManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * Custom Glide module for the Bitwarden app that configures Glide to use an OkHttpClient
 * with mTLS (mutual TLS) support.
 *
 * This ensures that all icon/image loading requests through Glide present the client certificate
 * for mutual TLS authentication, allowing them to pass through Cloudflare's mTLS checks.
 *
 * The configuration mirrors the SSL setup used in RetrofitsImpl for API calls.
 */
@GlideModule
class BitwardenAppGlideModule : AppGlideModule() {

    /**
     * Entry point to access Hilt-provided dependencies from non-Hilt managed classes.
     */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface BitwardenGlideEntryPoint {
        /**
         * Provides access to [CertificateManager] for mTLS certificate management.
         */
        fun certificateManager(): CertificateManager
    }

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        // Get CertificateManager from Hilt
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            BitwardenGlideEntryPoint::class.java,
        )
        val certificateManager = entryPoint.certificateManager()

        // Create OkHttpClient with mTLS configuration
        val okHttpClient = createMtlsOkHttpClient(certificateManager)

        // Register custom ModelLoader that uses our mTLS OkHttpClient
        registry.replace(
            GlideUrl::class.java,
            InputStream::class.java,
            OkHttpModelLoaderFactory(okHttpClient),
        )
    }

    /**
     * Custom ModelLoaderFactory for Glide 5.x that uses our mTLS-configured OkHttpClient.
     */
    private class OkHttpModelLoaderFactory(
        private val client: OkHttpClient,
    ) : ModelLoaderFactory<GlideUrl, InputStream> {

        override fun build(
            multiFactory: MultiModelLoaderFactory,
        ): ModelLoader<GlideUrl, InputStream> = OkHttpModelLoader(client)

        override fun teardown() {
            // No-op
        }
    }

    /**
     * Custom ModelLoader that uses OkHttpClient to load images.
     */
    private class OkHttpModelLoader(
        private val client: OkHttpClient,
    ) : ModelLoader<GlideUrl, InputStream> {

        override fun buildLoadData(
            model: GlideUrl,
            width: Int,
            height: Int,
            options: Options,
        ): ModelLoader.LoadData<InputStream>? {
            return ModelLoader.LoadData(model, OkHttpDataFetcher(client, model))
        }

        override fun handles(model: GlideUrl): Boolean = true
    }

    /**
     * DataFetcher that uses OkHttpClient to execute HTTP requests.
     */
    private class OkHttpDataFetcher(
        private val client: OkHttpClient,
        private val url: GlideUrl,
    ) : com.bumptech.glide.load.data.DataFetcher<InputStream> {

        private var call: Call? = null

        override fun loadData(
            priority: com.bumptech.glide.Priority,
            callback: com.bumptech.glide.load.data.DataFetcher.DataCallback<in InputStream>,
        ) {
            val request = Request.Builder()
                .url(url.toStringUrl())
                .build()

            call = client.newCall(request)

            try {
                val response = call?.execute()
                if (response?.isSuccessful == true) {
                    callback.onDataReady(response.body?.byteStream())
                } else {
                    callback.onLoadFailed(Exception("HTTP ${response?.code}: ${response?.message}"))
                }
            } catch (e: IOException) {
                callback.onLoadFailed(e)
            }
        }

        override fun cleanup() {
            // Response body cleanup is handled by Glide
        }

        override fun cancel() {
            call?.cancel()
        }

        override fun getDataClass(): Class<InputStream> = InputStream::class.java

        override fun getDataSource(): com.bumptech.glide.load.DataSource =
            com.bumptech.glide.load.DataSource.REMOTE
    }

    /**
     * Creates an OkHttpClient configured with mTLS using the same SSL setup as RetrofitsImpl.
     *
     * This client will present the client certificate stored in the Android KeyStore during
     * the TLS handshake.
     */
    private fun createMtlsOkHttpClient(certificateProvider: CertificateProvider): OkHttpClient {
        val sslContext = createSslContext(certificateProvider)
        val trustManagers = createSslTrustManagers()

        return OkHttpClient.Builder()
            .sslSocketFactory(
                sslContext.socketFactory,
                trustManagers.first() as X509TrustManager,
            )
            .build()
    }

    /**
     * Creates an SSLContext configured with a custom X509ExtendedKeyManager.
     *
     * This wraps our CertificateProvider to handle client certificate selection during
     * the TLS handshake.
     */
    private fun createSslContext(certificateProvider: CertificateProvider): SSLContext =
        SSLContext.getInstance("TLS").apply {
            init(
                arrayOf(
                    CertificateProviderKeyManager(certificateProvider = certificateProvider),
                ),
                createSslTrustManagers(),
                null,
            )
        }

    /**
     * X509ExtendedKeyManager implementation that delegates to a CertificateProvider.
     *
     * This is equivalent to BitwardenX509ExtendedKeyManager but defined locally since
     * that class is internal to the :network module.
     */
    private class CertificateProviderKeyManager(
        private val certificateProvider: CertificateProvider,
    ) : X509ExtendedKeyManager() {
        override fun chooseClientAlias(
            keyType: Array<out String>?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ): String = certificateProvider.chooseClientAlias(
            keyType = keyType,
            issuers = issuers,
            socket = socket,
        )

        override fun getCertificateChain(
            alias: String?,
        ): Array<X509Certificate>? = certificateProvider.getCertificateChain(alias)

        override fun getPrivateKey(alias: String?): PrivateKey? =
            certificateProvider.getPrivateKey(alias)

        // Unused server side methods
        override fun getServerAliases(
            alias: String?,
            issuers: Array<out Principal>?,
        ): Array<String> = emptyArray()

        override fun getClientAliases(
            keyType: String?,
            issuers: Array<out Principal>?,
        ): Array<String> = emptyArray()

        override fun chooseServerAlias(
            alias: String?,
            issuers: Array<out Principal>?,
            socket: Socket?,
        ): String = ""
    }

    /**
     * Creates default TrustManagers for verifying server certificates.
     *
     * This uses the system's default trust anchors (trusted CA certificates).
     */
    private fun createSslTrustManagers(): Array<TrustManager> =
        TrustManagerFactory
            .getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers
}
