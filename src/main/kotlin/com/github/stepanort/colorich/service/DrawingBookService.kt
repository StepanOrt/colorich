package com.github.stepanort.colorich.service

import com.github.stepanort.colorich.agy.AgyExecutor
import com.github.stepanort.colorich.config.AppConfig
import com.github.stepanort.colorich.immich.ImmichClient
import com.github.stepanort.colorich.immich.model.ActivityDto
import com.github.stepanort.colorich.immich.model.AssetDto
import com.github.stepanort.colorich.printer.IppPrinterService
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class DrawingBookService(
    private val config: AppConfig,
    private val immichClient: ImmichClient,
    private val agyExecutor: AgyExecutor,
    private val printerService: IppPrinterService
) {
    private val logger = LoggerFactory.getLogger(DrawingBookService::class.java)

    private val inProgressConversionAssetIds = ConcurrentHashMap.newKeySet<String>()
    private val inProgressPrintCommentIds = ConcurrentHashMap.newKeySet<String>()
    private val memoryFailedConversionAssetIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * Executes one polling cycle:
     * 1. Finds newly added original photos in the album, converts them to drawing book PNGs,
     *    uploads the PNG back to the album, adds a comment linking to the original,
     *    and removes the original from the album.
     * 2. Checks comments for "#print" on pictures in the album and dispatches print jobs.
     */
    suspend fun pollOnce() {
        logger.info("Polling Immich shared album ${config.sharedAlbumId}...")

        // 1. Fetch album activities/comments and album assets
        val activities: List<ActivityDto> = try {
            immichClient.getActivities(config.sharedAlbumId)
        } catch (e: Exception) {
            logger.error("Failed to retrieve activities from Immich: ${e.message}", e)
            emptyList()
        }

        val albumAssets: List<AssetDto>? = try {
            immichClient.getAlbumAssets(config.sharedAlbumId)
        } catch (e: Exception) {
            logger.error("Failed to retrieve assets from album ${config.sharedAlbumId}: ${e.message}", e)
            null
        }

        // 2. Process newly added original photos
        if (albumAssets != null) {
            processNewOriginalPhotos(albumAssets, activities)
        }

        // 3. Process "#print" comments
        processPrintComments(activities, albumAssets.orEmpty())
    }

    private suspend fun processNewOriginalPhotos(albumAssets: List<AssetDto>, activities: List<ActivityDto>) {
        val generatedAssetIdsWithOriginalRef = buildGeneratedAssetIds(activities)
        val failedConversionRefs = buildFailedConversionRefs(activities)

        val photosToConvert = albumAssets.filter { asset ->
            asset.id.isNotBlank() &&
            isImageAsset(asset) &&
            !isDrawingBookAsset(asset, generatedAssetIdsWithOriginalRef) &&
            !failedConversionRefs.contains(asset.id) &&
            !inProgressConversionAssetIds.contains(asset.id) &&
            !memoryFailedConversionAssetIds.contains(asset.id)
        }

        if (photosToConvert.isEmpty()) {
            logger.debug("No new original photos in album needing drawing-book conversion.")
            return
        }

        logger.info("Found ${photosToConvert.size} new original photo(s) to convert to drawing book style.")

        for (asset in photosToConvert) {
            val assetId = asset.id
            inProgressConversionAssetIds.add(assetId)
            try {
                processPhotoConversion(asset)
            } catch (e: Exception) {
                logger.error("Error converting photo $assetId to drawing book: ${e.message}", e)
                memoryFailedConversionAssetIds.add(assetId)
                try {
                    immichClient.postComment(
                        albumId = config.sharedAlbumId,
                        assetId = assetId,
                        comment = "Failed to convert to drawing book page: ${e.message} [conv-failed: $assetId]"
                    )
                } catch (commentEx: Exception) {
                    logger.error("Failed to post error comment: ${commentEx.message}", commentEx)
                }
            } finally {
                inProgressConversionAssetIds.remove(assetId)
            }
        }
    }

    private suspend fun processPhotoConversion(asset: AssetDto) {
        val assetId = asset.id
        val safeId = safeFilePrefix(assetId)
        logger.info("Processing photo conversion for asset $assetId (${asset.originalFileName})")

        val tempPhoto = File(config.tempDir, "${safeId}.${originalExtension(asset)}")
        val outputPng = File(config.tempDir, "${safeId}_drawing_book.png")

        try {
            // 1. Download original photo
            immichClient.downloadAssetOriginal(assetId, tempPhoto)

            // 2. Convert to drawing book PNG via agy CLI
            agyExecutor.convertPhotoToColoringPagePng(tempPhoto, outputPng)

            // 3. Upload generated PNG to Immich
            val newAssetId = immichClient.uploadAsset(outputPng)

            // 4. Add the generated PNG into the shared album
            immichClient.addAssetsToAlbum(config.sharedAlbumId, listOf(newAssetId))

            // 5. Post comment linking to original photo (best-effort once page is in album)
            val originalPhotoUrl = "${config.immichServerUrl}/photos/$assetId"
            val commentText = "Original photo: $originalPhotoUrl [original: $assetId]"
            try {
                immichClient.postComment(
                    albumId = config.sharedAlbumId,
                    assetId = newAssetId,
                    comment = commentText
                )
            } catch (e: Exception) {
                logger.warn("Added drawing book asset $newAssetId to album, but failed to post link comment: ${e.message}", e)
            }

            // 6. Remove original photo from the shared album
            try {
                immichClient.removeAssetsFromAlbum(config.sharedAlbumId, listOf(assetId))
                logger.info("Successfully converted asset $assetId -> new drawing book asset $newAssetId and removed original from album.")
            } catch (e: Exception) {
                // Avoid re-converting the original photo or posting a misleading "conversion failed" comment
                memoryFailedConversionAssetIds.add(assetId)
                logger.error("Converted asset $assetId -> $newAssetId, but failed to remove original asset from album: ${e.message}", e)
            }
        } finally {
            if (tempPhoto.exists()) tempPhoto.delete()
            if (outputPng.exists()) outputPng.delete()
        }
    }

    private suspend fun processPrintComments(activities: List<ActivityDto>, albumAssets: List<AssetDto>) {
        val handledCommentRefs = buildHandledCommentRefs(activities)
        val assetsById = albumAssets.associateBy { it.id }

        val printRequests = activities.filter { activity ->
            activity.type == "comment" &&
            activity.comment?.trim()?.lowercase()?.contains("#print") == true &&
            !activity.assetId.isNullOrBlank() &&
            !handledCommentRefs.contains(activity.id) &&
            !inProgressPrintCommentIds.contains(activity.id)
        }

        if (printRequests.isEmpty()) {
            logger.debug("No new unhandled '#print' comments found.")
            return
        }

        logger.info("Found ${printRequests.size} new '#print' request(s) to process.")

        for (request in printRequests) {
            val commentId = request.id
            val assetId = request.assetId!!
            val ext = assetsById[assetId]?.let { originalExtension(it, defaultExt = "png") } ?: "png"

            inProgressPrintCommentIds.add(commentId)
            try {
                processPrintRequest(commentId = commentId, assetId = assetId, extension = ext)
            } catch (e: Exception) {
                logger.error("Error processing print request for comment $commentId: ${e.message}", e)
                try {
                    immichClient.postComment(
                        albumId = config.sharedAlbumId,
                        assetId = assetId,
                        comment = "Failed to print: ${e.message} [ref: $commentId]"
                    )
                } catch (commentEx: Exception) {
                    logger.error("Failed to post error comment: ${commentEx.message}", commentEx)
                }
            } finally {
                inProgressPrintCommentIds.remove(commentId)
            }
        }
    }

    private suspend fun processPrintRequest(commentId: String, assetId: String, extension: String) {
        logger.info("Processing '#print' comment $commentId for asset $assetId")

        // 1. Post initial acknowledgment comment
        immichClient.postComment(
            albumId = config.sharedAlbumId,
            assetId = assetId,
            comment = "Queued for printing! [ref: $commentId]"
        )

        // 2. Download picture from Immich preserving its file extension
        val safeId = safeFilePrefix(assetId)
        val tempImage = File(config.tempDir, "${safeId}_print.$extension")

        try {
            immichClient.downloadAssetOriginal(assetId, tempImage)

            // 3. Print image
            printerService.printImage(tempImage)

            // 4. Post completion comment referencing the original comment UUID
            immichClient.postComment(
                albumId = config.sharedAlbumId,
                assetId = assetId,
                comment = "Printed successfully! [ref: $commentId]"
            )
            logger.info("Finished print request $commentId successfully.")
        } finally {
            if (tempImage.exists()) tempImage.delete()
        }
    }

    /**
     * Sanitizes an asset or comment identifier for safe use in local temporary file names.
     */
    internal fun safeFilePrefix(id: String): String =
        id.replace(Regex("[^a-zA-Z0-9_-]+"), "_").trim('_').ifBlank { "asset" }

    /**
     * Determines whether an asset is a still image (videos and other types are skipped).
     */
    fun isImageAsset(asset: AssetDto): Boolean = asset.type.equals("IMAGE", ignoreCase = true)

    /**
     * Returns the lower-cased file extension of the asset's original file name, defaulting to [defaultExt].
     */
    fun originalExtension(asset: AssetDto, defaultExt: String = "jpg"): String {
        val ext = asset.originalFileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return if (ext.isNotBlank() && ext.all { it.isLetterOrDigit() }) ext else defaultExt
    }

    /**
     * Determines whether an asset is already a generated drawing-book picture.
     */
    fun isDrawingBookAsset(asset: AssetDto, generatedAssetIdsWithOriginalRef: Set<String>): Boolean {
        val filename = asset.originalFileName?.lowercase() ?: ""
        if (filename.contains("drawing_book") || filename.contains("coloring_page")) {
            return true
        }
        return generatedAssetIdsWithOriginalRef.contains(asset.id)
    }

    /**
     * Extracts UUIDs of generated assets that have "[original: <UUID>]" comments.
     */
    fun buildGeneratedAssetIds(activities: List<ActivityDto>): Set<String> {
        val originalRefRegex = Regex("""\[original:\s*([a-zA-Z0-9_-]+)\]""")
        val assetIds = mutableSetOf<String>()
        for (activity in activities) {
            val text = activity.comment ?: continue
            if (originalRefRegex.containsMatchIn(text) && !activity.assetId.isNullOrBlank()) {
                assetIds.add(activity.assetId)
            }
        }
        return assetIds
    }

    /**
     * Extracts UUIDs from comments containing "[conv-failed: <UUID>]".
     */
    fun buildFailedConversionRefs(activities: List<ActivityDto>): Set<String> {
        val failedRegex = Regex("""\[conv-failed:\s*([a-zA-Z0-9_-]+)\]""")
        val refs = mutableSetOf<String>()
        for (activity in activities) {
            val text = activity.comment ?: continue
            val match = failedRegex.find(text)
            if (match != null) {
                refs.add(match.groupValues[1])
            }
        }
        return refs
    }

    /**
     * Extracts UUIDs from comments containing "[ref: <UUID>]".
     */
    fun buildHandledCommentRefs(activities: List<ActivityDto>): Set<String> {
        val refRegex = Regex("""\[ref:\s*([a-zA-Z0-9_-]+)\]""")
        val refs = mutableSetOf<String>()
        for (activity in activities) {
            val text = activity.comment ?: continue
            val match = refRegex.find(text)
            if (match != null) {
                refs.add(match.groupValues[1])
            }
        }
        return refs
    }

    /**
     * Runs continuous polling loop.
     */
    suspend fun startPollingLoop(scope: CoroutineScope) {
        logger.info("Starting Immich polling loop every ${config.pollIntervalSeconds} seconds...")
        while (scope.isActive) {
            try {
                pollOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Unexpected error during polling cycle: ${e.message}", e)
            }
            delay(config.pollIntervalSeconds * 1000L)
        }
    }
}
