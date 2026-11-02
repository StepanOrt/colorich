package com.github.stepanort.colorich.immich

import com.github.stepanort.colorich.config.AppConfig
import com.github.stepanort.colorich.immich.model.*
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.jvm.javaio.*
import io.ktor.utils.io.streams.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream

class ImmichClient(
    private val config: AppConfig,
    engine: HttpClientEngine = CIO.create()
) : AutoCloseable {
    private val logger = LoggerFactory.getLogger(ImmichClient::class.java)

    val httpClient = HttpClient(engine) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
                encodeDefaults = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 60_000
        }
        install(Logging) {
            this.logger = object : Logger {
                override fun log(message: String) {
                    this@ImmichClient.logger.info("Ktor: {}", message)
                }
            }
            level = LogLevel.INFO
            sanitizeHeader { header -> header.equals("x-api-key", ignoreCase = true) }
        }
    }

    private val baseUrl = "${config.immichServerUrl}/api"

    private fun HttpRequestBuilder.applyAuthAndHeaders() {
        header("x-api-key", config.immichApiKey)
        header(HttpHeaders.Accept, "application/json")
    }

    suspend fun getActivities(albumId: String = config.sharedAlbumId): List<ActivityDto> {
        logger.debug("Fetching activities for album: $albumId")
        val response = httpClient.get("$baseUrl/activities") {
            applyAuthAndHeaders()
            parameter("albumId", albumId)
            parameter("type", "comment")
        }
        if (!response.status.isSuccess()) {
            val err = response.bodyAsText()
            throw IllegalStateException("Failed to get activities (HTTP ${response.status}): $err")
        }
        return response.body()
    }

    suspend fun postComment(
        albumId: String = config.sharedAlbumId,
        assetId: String?,
        comment: String
    ): ActivityDto {
        logger.info("Posting comment to album: $albumId, asset: $assetId -> $comment")
        val body = CreateActivityDto(
            albumId = albumId,
            assetId = assetId,
            comment = comment,
            type = "comment"
        )
        val response = httpClient.post("$baseUrl/activities") {
            applyAuthAndHeaders()
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            val err = response.bodyAsText()
            throw IllegalStateException("Failed to post comment (HTTP ${response.status}): $err")
        }
        return response.body()
    }

    suspend fun downloadAssetOriginal(assetId: String, destinationFile: File): File {
        logger.info("Downloading asset original: $assetId to ${destinationFile.absolutePath}")
        val response = httpClient.get("$baseUrl/assets/$assetId/original") {
            header("x-api-key", config.immichApiKey)
        }
        if (!response.status.isSuccess()) {
            val err = response.bodyAsText()
            throw IllegalStateException("Failed to download asset $assetId (HTTP ${response.status}): $err")
        }
        destinationFile.parentFile?.mkdirs()
        response.bodyAsChannel().toInputStream().use { input ->
            FileOutputStream(destinationFile).use { output ->
                input.copyTo(output)
            }
        }
        return destinationFile
    }

    suspend fun getAlbumAssets(albumId: String = config.sharedAlbumId): List<AssetDto> {
        logger.debug("Fetching assets for album: $albumId")
        val allAssets = mutableListOf<AssetDto>()
        var page = 1
        while (true) {
            val response = httpClient.post("$baseUrl/search/metadata") {
                applyAuthAndHeaders()
                contentType(ContentType.Application.Json)
                setBody(MetadataSearchDto(albumIds = listOf(albumId), page = page, size = SEARCH_PAGE_SIZE))
            }
            if (!response.status.isSuccess()) {
                val err = response.bodyAsText()
                throw IllegalStateException("Failed to search album assets (HTTP ${response.status}): $err")
            }
            val result: SearchResultDto = response.body()
            allAssets += result.assets.items
            val nextPage = result.assets.nextPage?.toIntOrNull()
            if (nextPage == null || nextPage <= page || result.assets.items.isEmpty()) break
            page = nextPage
        }
        return allAssets
    }

    suspend fun uploadAsset(file: File): String {
        logger.info("Uploading asset to Immich: ${file.name} (${file.length()} bytes)")
        val now = java.time.Instant.now().toString()
        val deviceAssetId = "${file.nameWithoutExtension}-${System.currentTimeMillis()}"

        val response = httpClient.submitFormWithBinaryData(
            url = "$baseUrl/assets",
            formData = formData {
                append("deviceAssetId", deviceAssetId)
                append("deviceId", "drawing-book-service")
                append("fileCreatedAt", now)
                append("fileModifiedAt", now)
                append(
                    "assetData",
                    InputProvider(file.length()) { file.inputStream().asInput() },
                    Headers.build {
                        append(HttpHeaders.ContentType, "image/png")
                        append(HttpHeaders.ContentDisposition, "filename=\"${file.name}\"")
                    }
                )
            }
        ) {
            applyAuthAndHeaders()
        }

        if (!response.status.isSuccess()) {
            val err = response.bodyAsText()
            throw IllegalStateException("Failed to upload asset (HTTP ${response.status}): $err")
        }
        val uploaded: UploadAssetResponseDto = response.body()
        logger.info("Asset uploaded successfully: ${uploaded.id}")
        return uploaded.id
    }

    suspend fun addAssetsToAlbum(albumId: String = config.sharedAlbumId, assetIds: List<String>) {
        if (assetIds.isEmpty()) return
        logger.info("Adding ${assetIds.size} asset(s) to album $albumId")
        val response = httpClient.put("$baseUrl/albums/$albumId/assets") {
            applyAuthAndHeaders()
            contentType(ContentType.Application.Json)
            setBody(BulkIdsDto(ids = assetIds))
        }
        if (!response.status.isSuccess()) {
            val err = response.bodyAsText()
            throw IllegalStateException("Failed to add assets to album (HTTP ${response.status}): $err")
        }
    }

    suspend fun removeAssetsFromAlbum(albumId: String = config.sharedAlbumId, assetIds: List<String>) {
        if (assetIds.isEmpty()) return
        logger.info("Removing ${assetIds.size} asset(s) from album $albumId")
        val response = httpClient.delete("$baseUrl/albums/$albumId/assets") {
            applyAuthAndHeaders()
            contentType(ContentType.Application.Json)
            setBody(BulkIdsDto(ids = assetIds))
        }
        if (!response.status.isSuccess()) {
            val err = response.bodyAsText()
            throw IllegalStateException("Failed to remove assets from album (HTTP ${response.status}): $err")
        }
    }

    override fun close() {
        httpClient.close()
    }

    companion object {
        private const val SEARCH_PAGE_SIZE = 250
    }
}
