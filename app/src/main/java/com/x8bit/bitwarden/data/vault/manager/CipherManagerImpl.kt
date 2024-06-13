package com.x8bit.bitwarden.data.vault.manager

import android.net.Uri
import com.bitwarden.vault.AttachmentView
import com.bitwarden.vault.Cipher
import com.bitwarden.vault.CipherView
import com.x8bit.bitwarden.data.auth.datasource.disk.AuthDiskSource
import com.x8bit.bitwarden.data.platform.util.asFailure
import com.x8bit.bitwarden.data.platform.util.asSuccess
import com.x8bit.bitwarden.data.platform.util.flatMap
import com.x8bit.bitwarden.data.vault.datasource.disk.VaultDiskSource
import com.x8bit.bitwarden.data.vault.datasource.network.model.CreateCipherInOrganizationJsonRequest
import com.x8bit.bitwarden.data.vault.datasource.network.model.ShareCipherJsonRequest
import com.x8bit.bitwarden.data.vault.datasource.network.model.UpdateCipherCollectionsJsonRequest
import com.x8bit.bitwarden.data.vault.datasource.network.model.UpdateCipherResponseJson
import com.x8bit.bitwarden.data.vault.datasource.network.service.CiphersService
import com.x8bit.bitwarden.data.vault.datasource.sdk.VaultSdkSource
import com.x8bit.bitwarden.data.vault.manager.model.DownloadResult
import com.x8bit.bitwarden.data.vault.repository.model.CreateAttachmentResult
import com.x8bit.bitwarden.data.vault.repository.model.CreateCipherResult
import com.x8bit.bitwarden.data.vault.repository.model.DeleteAttachmentResult
import com.x8bit.bitwarden.data.vault.repository.model.DeleteCipherResult
import com.x8bit.bitwarden.data.vault.repository.model.DownloadAttachmentResult
import com.x8bit.bitwarden.data.vault.repository.model.RestoreCipherResult
import com.x8bit.bitwarden.data.vault.repository.model.ShareCipherResult
import com.x8bit.bitwarden.data.vault.repository.model.UpdateCipherResult
import com.x8bit.bitwarden.data.vault.repository.util.toEncryptedNetworkCipher
import com.x8bit.bitwarden.data.vault.repository.util.toEncryptedNetworkCipherResponse
import com.x8bit.bitwarden.data.vault.repository.util.toEncryptedSdkCipher
import com.x8bit.bitwarden.data.vault.repository.util.toNetworkAttachmentRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.time.Clock

/**
 * The default implementation of the [CipherManager].
 */
@Suppress("TooManyFunctions")
class CipherManagerImpl(
    private val fileManager: FileManager,
    private val authDiskSource: AuthDiskSource,
    private val ciphersService: CiphersService,
    private val vaultDiskSource: VaultDiskSource,
    private val vaultSdkSource: VaultSdkSource,
    private val clock: Clock,
) : CipherManager {
    private val activeUserId: String? get() = authDiskSource.userState?.activeUserId

    override suspend fun createCipher(cipherView: CipherView): CreateCipherResult {
        val userId = activeUserId ?: return CreateCipherResult.Error
        return vaultSdkSource
            .encryptCipher(
                userId = userId,
                cipherView = cipherView,
            )
            .flatMap { ciphersService.createCipher(body = it.toEncryptedNetworkCipher()) }
            .onSuccess { vaultDiskSource.saveCipher(userId = userId, cipher = it) }
            .fold(
                onFailure = { CreateCipherResult.Error },
                onSuccess = { CreateCipherResult.Success },
            )
    }

    override suspend fun createCipherInOrganization(
        cipherView: CipherView,
        collectionIds: List<String>,
    ): CreateCipherResult {
        val userId = activeUserId ?: return CreateCipherResult.Error
        return vaultSdkSource
            .encryptCipher(
                userId = userId,
                cipherView = cipherView,
            )
            .flatMap { cipher ->
                ciphersService.createCipherInOrganization(
                    body = CreateCipherInOrganizationJsonRequest(
                        cipher = cipher.toEncryptedNetworkCipher(),
                        collectionIds = collectionIds,
                    ),
                )
            }
            .onSuccess {
                vaultDiskSource.saveCipher(
                    userId = userId,
                    cipher = it.copy(collectionIds = collectionIds),
                )
            }
            .fold(
                onFailure = { CreateCipherResult.Error },
                onSuccess = { CreateCipherResult.Success },
            )
    }

    override suspend fun hardDeleteCipher(cipherId: String): DeleteCipherResult {
        val userId = activeUserId ?: return DeleteCipherResult.Error
        return ciphersService
            .hardDeleteCipher(cipherId = cipherId)
            .onSuccess { vaultDiskSource.deleteCipher(userId = userId, cipherId = cipherId) }
            .fold(
                onSuccess = { DeleteCipherResult.Success },
                onFailure = { DeleteCipherResult.Error },
            )
    }

    override suspend fun softDeleteCipher(
        cipherId: String,
        cipherView: CipherView,
    ): DeleteCipherResult {
        val userId = activeUserId ?: return DeleteCipherResult.Error
        return cipherView
            .encryptCipherAndCheckForMigration(userId = userId, cipherId = cipherId)
            .flatMap { cipher ->
                ciphersService
                    .softDeleteCipher(cipherId = cipherId)
                    .flatMap { vaultSdkSource.decryptCipher(userId = userId, cipher = cipher) }
            }
            .flatMap {
                vaultSdkSource.encryptCipher(
                    userId = userId,
                    cipherView = it.copy(deletedDate = clock.instant()),
                )
            }
            .onSuccess {
                vaultDiskSource.saveCipher(
                    userId = userId,
                    cipher = it.toEncryptedNetworkCipherResponse(),
                )
            }
            .fold(
                onSuccess = { DeleteCipherResult.Success },
                onFailure = { DeleteCipherResult.Error },
            )
    }

    override suspend fun deleteCipherAttachment(
        cipherId: String,
        attachmentId: String,
        cipherView: CipherView,
    ): DeleteAttachmentResult =
        deleteCipherAttachmentForResult(
            cipherId = cipherId,
            attachmentId = attachmentId,
            cipherView = cipherView,
        )
            .fold(
                onSuccess = { DeleteAttachmentResult.Success },
                onFailure = { DeleteAttachmentResult.Error },
            )

    private suspend fun deleteCipherAttachmentForResult(
        cipherId: String,
        attachmentId: String,
        cipherView: CipherView,
    ): Result<Cipher> {
        val userId = activeUserId ?: return IllegalStateException("No active user").asFailure()
        return ciphersService
            .deleteCipherAttachment(
                cipherId = cipherId,
                attachmentId = attachmentId,
            )
            .flatMap {
                cipherView
                    .copy(
                        attachments = cipherView.attachments?.mapNotNull {
                            if (it.id == attachmentId) null else it
                        },
                    )
                    .encryptCipherAndCheckForMigration(userId = userId, cipherId = cipherId)
            }
            .onSuccess { cipher ->
                vaultDiskSource.saveCipher(
                    userId = userId,
                    cipher = cipher.toEncryptedNetworkCipherResponse(),
                )
            }
    }

    override suspend fun restoreCipher(
        cipherId: String,
        cipherView: CipherView,
    ): RestoreCipherResult {
        val userId = activeUserId ?: return RestoreCipherResult.Error
        return ciphersService
            .restoreCipher(cipherId = cipherId)
            .onSuccess {
                vaultDiskSource.saveCipher(
                    userId = userId,
                    cipher = it.copy(collectionIds = cipherView.collectionIds),
                )
            }
            .fold(
                onSuccess = { RestoreCipherResult.Success },
                onFailure = { RestoreCipherResult.Error },
            )
    }

    override suspend fun updateCipher(
        cipherId: String,
        cipherView: CipherView,
    ): UpdateCipherResult {
        val userId = activeUserId ?: return UpdateCipherResult.Error(errorMessage = null)
        return vaultSdkSource
            .encryptCipher(
                userId = userId,
                cipherView = cipherView,
            )
            .flatMap { cipher ->
                ciphersService.updateCipher(
                    cipherId = cipherId,
                    body = cipher.toEncryptedNetworkCipher(),
                )
            }
            .map { response ->
                when (response) {
                    is UpdateCipherResponseJson.Invalid -> {
                        UpdateCipherResult.Error(errorMessage = response.message)
                    }

                    is UpdateCipherResponseJson.Success -> {
                        vaultDiskSource.saveCipher(
                            userId = userId,
                            cipher = response.cipher.copy(collectionIds = cipherView.collectionIds),
                        )
                        UpdateCipherResult.Success
                    }
                }
            }
            .fold(
                onFailure = { UpdateCipherResult.Error(errorMessage = null) },
                onSuccess = { it },
            )
    }

    override suspend fun shareCipher(
        cipherId: String,
        organizationId: String,
        cipherView: CipherView,
        collectionIds: List<String>,
    ): ShareCipherResult {
        val userId = activeUserId ?: return ShareCipherResult.Error
        return vaultSdkSource
            .moveToOrganization(
                userId = userId,
                organizationId = organizationId,
                cipherView = cipherView,
            )
            .flatMap {
                migrateAttachments(
                    userId = userId,
                    cipherView = it,
                    organizationId = organizationId,
                )
            }
            .flatMap { cipher ->
                ciphersService.shareCipher(
                    cipherId = cipherId,
                    body = ShareCipherJsonRequest(
                        cipher = cipher.toEncryptedNetworkCipher(),
                        collectionIds = collectionIds,
                    ),
                )
            }
            .onSuccess {
                vaultDiskSource.saveCipher(
                    userId = userId,
                    cipher = it.copy(collectionIds = collectionIds),
                )
            }
            .fold(
                onFailure = { ShareCipherResult.Error },
                onSuccess = { ShareCipherResult.Success },
            )
    }

    override suspend fun updateCipherCollections(
        cipherId: String,
        cipherView: CipherView,
        collectionIds: List<String>,
    ): ShareCipherResult {
        val userId = activeUserId ?: return ShareCipherResult.Error
        return ciphersService
            .updateCipherCollections(
                cipherId = cipherId,
                body = UpdateCipherCollectionsJsonRequest(collectionIds = collectionIds),
            )
            .flatMap {
                vaultSdkSource.encryptCipher(
                    userId = userId,
                    cipherView = cipherView.copy(collectionIds = collectionIds),
                )
            }
            .onSuccess { cipher ->
                vaultDiskSource.saveCipher(
                    userId = userId,
                    cipher = cipher.toEncryptedNetworkCipherResponse(),
                )
            }
            .fold(
                onSuccess = { ShareCipherResult.Success },
                onFailure = { ShareCipherResult.Error },
            )
    }

    override suspend fun createAttachment(
        cipherId: String,
        cipherView: CipherView,
        fileSizeBytes: String,
        fileName: String,
        fileUri: Uri,
    ): CreateAttachmentResult =
        createAttachmentForResult(
            cipherId = cipherId,
            cipherView = cipherView,
            fileSizeBytes = fileSizeBytes,
            fileName = fileName,
            fileUri = fileUri,
        )
            .fold(
                onFailure = { CreateAttachmentResult.Error },
                onSuccess = { CreateAttachmentResult.Success(cipherView = it) },
            )

    @Suppress("LongMethod")
    private suspend fun createAttachmentForResult(
        cipherId: String,
        cipherView: CipherView,
        fileSizeBytes: String?,
        fileName: String?,
        fileUri: Uri,
    ): Result<CipherView> {
        val userId = activeUserId ?: return IllegalStateException("No active user").asFailure()
        val attachmentView = AttachmentView(
            id = null,
            url = null,
            size = fileSizeBytes,
            sizeName = null,
            fileName = fileName,
            key = null,
        )
        return cipherView
            .encryptCipherAndCheckForMigration(
                userId = userId,
                cipherId = requireNotNull(cipherView.id),
            )
            .flatMap { cipher ->
                fileManager
                    .writeUriToCache(fileUri = fileUri)
                    .flatMap { cacheFile ->
                        vaultSdkSource
                            .encryptAttachment(
                                userId = userId,
                                cipher = cipher,
                                attachmentView = attachmentView,
                                decryptedFilePath = cacheFile.absolutePath,
                                encryptedFilePath = "${cacheFile.absolutePath}.enc",
                            )
                            .flatMap { attachment ->
                                ciphersService
                                    .createAttachment(
                                        cipherId = cipherId,
                                        body = attachment.toNetworkAttachmentRequest(),
                                    )
                                    .flatMap { attachmentJsonResponse ->
                                        val encryptedFile = File("${cacheFile.absolutePath}.enc")
                                        ciphersService
                                            .uploadAttachment(
                                                attachmentJsonResponse = attachmentJsonResponse,
                                                encryptedFile = encryptedFile,
                                            )
                                            .onSuccess {
                                                fileManager.delete(cacheFile, encryptedFile)
                                            }
                                            .onFailure {
                                                fileManager.delete(cacheFile, encryptedFile)
                                            }
                                    }
                            }
                    }
            }
            .map { it.copy(collectionIds = cipherView.collectionIds) }
            .onSuccess {
                // Save the send immediately, regardless of whether the decrypt succeeds
                vaultDiskSource.saveCipher(userId = userId, cipher = it)
            }
            .flatMap {
                vaultSdkSource.decryptCipher(
                    userId = userId,
                    cipher = it.toEncryptedSdkCipher(),
                )
            }
    }

    override suspend fun downloadAttachment(
        cipherView: CipherView,
        attachmentId: String,
    ): DownloadAttachmentResult =
        downloadAttachmentForResult(
            cipherView = cipherView,
            attachmentId = attachmentId,
        )
            .fold(
                onSuccess = { DownloadAttachmentResult.Success(file = it) },
                onFailure = { DownloadAttachmentResult.Failure },
            )

    @Suppress("ReturnCount")
    private suspend fun downloadAttachmentForResult(
        cipherView: CipherView,
        attachmentId: String,
    ): Result<File> {
        val userId = activeUserId ?: return IllegalStateException("No active user").asFailure()

        val cipher = cipherView
            .encryptCipherAndCheckForMigration(
                userId = userId,
                cipherId = requireNotNull(cipherView.id),
            )
            .fold(
                onSuccess = { it },
                onFailure = { return it.asFailure() },
            )
        val attachment = cipher.attachments?.find { it.id == attachmentId }
            ?: return IllegalStateException("No attachment to download").asFailure()

        val attachmentData = ciphersService
            .getCipherAttachment(
                cipherId = requireNotNull(cipher.id),
                attachmentId = attachmentId,
            )
            .fold(
                onSuccess = { it },
                onFailure = { return it.asFailure() },
            )

        val url = attachmentData.url
            ?: return IllegalStateException("Attachment does not have a url").asFailure()

        val encryptedFile = when (val result = fileManager.downloadFileToCache(url)) {
            DownloadResult.Failure -> return IllegalStateException("Download failed").asFailure()
            is DownloadResult.Success -> result.file
        }

        val decryptedFile = File(encryptedFile.path + "_decrypted")
        return vaultSdkSource
            .decryptFile(
                userId = userId,
                cipher = cipher,
                attachment = attachment,
                encryptedFilePath = encryptedFile.path,
                decryptedFilePath = decryptedFile.path,
            )
            .onSuccess { fileManager.delete(encryptedFile) }
            .onFailure { fileManager.delete(encryptedFile) }
            .map { decryptedFile }
    }

    /**
     * A helper method to check if the [CipherView] needs to be migrated when you encrypt it.
     */
    private suspend fun CipherView.encryptCipherAndCheckForMigration(
        userId: String,
        cipherId: String,
    ): Result<Cipher> =
        if (this.key == null) {
            vaultSdkSource
                .encryptCipher(userId = userId, cipherView = this)
                .flatMap {
                    ciphersService.updateCipher(
                        cipherId = cipherId,
                        body = it.toEncryptedNetworkCipher(),
                    )
                }
                .flatMap { response ->
                    when (response) {
                        is UpdateCipherResponseJson.Invalid -> {
                            IllegalStateException(response.message).asFailure()
                        }

                        is UpdateCipherResponseJson.Success -> {
                            vaultDiskSource.saveCipher(userId = userId, cipher = response.cipher)
                            response.cipher.toEncryptedSdkCipher().asSuccess()
                        }
                    }
                }
        } else {
            vaultSdkSource.encryptCipher(userId = userId, cipherView = this)
        }

    @Suppress("ReturnCount")
    private suspend fun migrateAttachments(
        userId: String,
        cipherView: CipherView,
        organizationId: String,
    ): Result<Cipher> {
        // Only run the migrations if we have attachments that do not have their own 'key'
        val attachmentViewsToMigrate = cipherView.attachments.orEmpty().filter { it.key == null }
        if (attachmentViewsToMigrate.none()) {
            return vaultSdkSource.encryptCipher(userId = userId, cipherView = cipherView)
        }

        val cipherViewId = cipherView.id
            ?: return IllegalStateException("CipherView must have an ID").asFailure()
        val cipher = vaultSdkSource
            .encryptCipher(userId = userId, cipherView = cipherView)
            .getOrElse { return it.asFailure() }

        // Gets a list of all the attachments that do not require migration
        // We will combine this with all migrated attachments at the end
        val attachmentsWithKeys = cipher.attachments.orEmpty().filter { it.key != null }

        val migrations = coroutineScope {
            attachmentViewsToMigrate.map { attachmentView ->
                async {
                    attachmentView
                        .id
                        ?.let { attachmentId ->
                            this@CipherManagerImpl
                                .downloadAttachmentForResult(
                                    cipherView = cipherView,
                                    attachmentId = attachmentId,
                                )
                                .flatMap { decryptedFile ->
                                    val encryptedFile = File("${decryptedFile.absolutePath}.enc")
                                    // Re-encrypting the attachment will generate the `key` and
                                    // we need to encrypt the associated file with that `key`
                                    vaultSdkSource
                                        .encryptAttachment(
                                            userId = userId,
                                            cipher = cipher,
                                            attachmentView = attachmentView,
                                            decryptedFilePath = decryptedFile.absolutePath,
                                            encryptedFilePath = encryptedFile.absolutePath,
                                        )
                                        .onSuccess { fileManager.delete(decryptedFile) }
                                        .flatMap { attachment ->
                                            ciphersService
                                                .shareAttachment(
                                                    cipherId = cipherViewId,
                                                    attachment = attachment,
                                                    organizationId = organizationId,
                                                    encryptedFile = encryptedFile,
                                                )
                                                .onSuccess { fileManager.delete(encryptedFile) }
                                                .map { attachment }
                                        }
                                }
                        }
                        ?: IllegalStateException("AttachmentView must have an ID").asFailure()
                }
            }
        }

        // We are collecting the migrated attachments to combine with the un-migrated attachments
        // If anything fails, we consider the entire process to be a failure
        val migratedAttachments = awaitAll(*migrations.toTypedArray()).map {
            it.getOrElse { error -> return error.asFailure() }
        }
        return cipher.copy(attachments = attachmentsWithKeys + migratedAttachments).asSuccess()
    }
}
