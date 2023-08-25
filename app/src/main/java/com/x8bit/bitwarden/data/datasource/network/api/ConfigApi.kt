package com.x8bit.bitwarden.data.datasource.network.api

import com.x8bit.bitwarden.data.datasource.network.models.ConfigResponseJson
import retrofit2.http.GET

/**
 * This interface defines the API service for fetching configuration data.
 */
interface ConfigApi {
    /**
     * Retrieves the configuration data from the server.
     *
     * @return A [ConfigResponseJson] containing the configuration response model.
     */
    @GET("config")
    suspend fun getConfig(): Result<ConfigResponseJson>
}
