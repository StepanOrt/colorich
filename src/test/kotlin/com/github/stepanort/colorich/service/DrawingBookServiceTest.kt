package com.github.stepanort.colorich.service

import com.github.stepanort.colorich.agy.AgyExecutor
import com.github.stepanort.colorich.config.AppConfig
import com.github.stepanort.colorich.immich.ImmichClient
import com.github.stepanort.colorich.immich.model.ActivityDto
import com.github.stepanort.colorich.immich.model.AssetDto
import com.github.stepanort.colorich.printer.IppPrinterService
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class DrawingBookServiceTest {

    private val testConfig = AppConfig(
        immichServerUrl = "http://localhost:3001",
        immichApiKey = "test-key",
        sharedAlbumId = "album-123",
        printerName = "Test_Printer",
        dryRun = true
    )

    private fun <T> withTestService(
        engine: MockEngine = MockEngine { error("No HTTP calls expected") },
        block: (DrawingBookService) -> T
    ): T {
        return ImmichClient(testConfig, engine = engine).use { client ->
            val service = DrawingBookService(
                testConfig,
                client,
                AgyExecutor(testConfig),
                IppPrinterService(testConfig)
            )
            block(service)
        }
    }

    @Test
    fun `test buildHandledCommentRefs finds UUIDs in ref tags`() = withTestService { service ->
        val activities = listOf(
            ActivityDto(id = "1", type = "comment", comment = "Nice photo!"),
            ActivityDto(id = "2", type = "comment", comment = "#print"),
            ActivityDto(id = "3", type = "comment", comment = "Queued for printing! [ref: c123-uuid]"),
            ActivityDto(id = "4", type = "comment", comment = "Printed successfully! [ref: c456-uuid]")
        )

        val refs = service.buildHandledCommentRefs(activities)
        assertEquals(setOf("c123-uuid", "c456-uuid"), refs)
    }

    @Test
    fun `test isDrawingBookAsset recognizes generated assets`() = withTestService { service ->
        val asset1 = AssetDto(id = "a1", originalFileName = "photo.jpg")
        val asset2 = AssetDto(id = "a2", originalFileName = "PXL_123_drawing_book.png")
        val asset3 = AssetDto(id = "a3", originalFileName = "something_coloring_page.png")
        val asset4 = AssetDto(id = "a4", originalFileName = "custom.png")

        assertFalse(service.isDrawingBookAsset(asset1, emptySet()))
        assertTrue(service.isDrawingBookAsset(asset2, emptySet()))
        assertTrue(service.isDrawingBookAsset(asset3, emptySet()))
        assertFalse(service.isDrawingBookAsset(asset4, emptySet()))
        assertTrue(service.isDrawingBookAsset(asset4, setOf("a4")))
    }

    @Test
    fun `test isImageAsset skips videos and unknown types`() = withTestService { service ->
        assertTrue(service.isImageAsset(AssetDto(id = "a1", type = "IMAGE")))
        assertFalse(service.isImageAsset(AssetDto(id = "a2", type = "VIDEO")))
        assertFalse(service.isImageAsset(AssetDto(id = "a3", type = null)))
    }

    @Test
    fun `test originalExtension keeps original format and sanitizes prefixes`() = withTestService { service ->
        assertEquals("heic", service.originalExtension(AssetDto(id = "a1", originalFileName = "IMG_0001.HEIC")))
        assertEquals("png", service.originalExtension(AssetDto(id = "a2", originalFileName = "screen.png")))
        assertEquals("jpg", service.originalExtension(AssetDto(id = "a3", originalFileName = "noext")))
        assertEquals("jpg", service.originalExtension(AssetDto(id = "a4", originalFileName = null)))
        assertEquals("a_b_c", service.safeFilePrefix("../a/b'c"))
    }

    @Test
    fun `test pollOnce skips assets with conv-failed comment, videos and fetches all pages`() = runTest {
        val requestedPages = mutableListOf<Int>()
        val unexpectedRequests = mutableListOf<String>()
        val mockEngine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/activities") && request.method == HttpMethod.Get -> {
                    respond(
                        content = """[
                            {"id": "c1", "assetId": "a1", "albumId": "album-123", "comment": "Failed to convert [conv-failed: a1]", "type": "comment"}
                        ]""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                path.endsWith("/search/metadata") && request.method == HttpMethod.Post -> {
                    val body = (request.body as io.ktor.http.content.TextContent).text
                    val page = Regex("\"page\"\\s*:\\s*(\\d+)").find(body)!!.groupValues[1].toInt()
                    requestedPages += page
                    val content = if (page == 1) {
                        """{"assets": {"items": [{"id": "a1", "originalFileName": "photo.jpg", "type": "IMAGE"}], "total": 1, "nextPage": "2"}}"""
                    } else {
                        """{"assets": {"items": [{"id": "v1", "originalFileName": "clip.mp4", "type": "VIDEO"}], "total": 1, "nextPage": null}}"""
                    }
                    respond(
                        content = content,
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> {
                    unexpectedRequests += "${request.method.value} $path"
                    respondError(HttpStatusCode.NotFound)
                }
            }
        }

        val client = ImmichClient(testConfig, engine = mockEngine)
        val service = DrawingBookService(testConfig, client, AgyExecutor(testConfig), IppPrinterService(testConfig))

        // Neither the previously failed photo nor the video may trigger a download/conversion.
        service.pollOnce()
        assertEquals(listOf(1, 2), requestedPages)
        assertEquals(emptyList<String>(), unexpectedRequests)
        client.close()
    }

    @Test
    fun `test pollOnce handles already processed comments and assets`() = runTest {
        val mockEngine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/activities") && request.method == HttpMethod.Get -> {
                    respond(
                        content = """[
                            {"id": "c1", "assetId": "a1", "albumId": "album-123", "comment": "#print", "type": "comment"},
                            {"id": "c2", "assetId": "a1", "albumId": "album-123", "comment": "Printed. [ref: c1]", "type": "comment"}
                        ]""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                path.endsWith("/search/metadata") && request.method == HttpMethod.Post -> {
                    respond(
                        content = """{
                            "assets": {
                                "items": [
                                    {"id": "a1", "originalFileName": "sample_drawing_book.png", "type": "IMAGE"}
                                ],
                                "total": 1
                            }
                        }""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                else -> error("Unhandled mock endpoint: $path")
            }
        }

        ImmichClient(testConfig, engine = mockEngine).use { client ->
            val service = DrawingBookService(testConfig, client, AgyExecutor(testConfig), IppPrinterService(testConfig))

            // pollOnce should complete without errors and not trigger conversion or printing on already-handled items
            service.pollOnce()
        }
    }

    @Test
    fun `test pollOnce prints PDF asset directly without invoking python conversion`() = runTest {
        val postedComments = mutableListOf<String>()
        val mockEngine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.endsWith("/activities") && request.method == HttpMethod.Get -> {
                    respond(
                        content = """[
                            {"id": "c-pdf", "assetId": "pdf-1", "albumId": "album-123", "comment": "#print", "type": "comment"}
                        ]""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                path.endsWith("/activities") && request.method == HttpMethod.Post -> {
                    val body = (request.body as io.ktor.http.content.TextContent).text
                    postedComments += body
                    respond(
                        content = """{"id": "c-reply", "assetId": "pdf-1", "albumId": "album-123", "comment": "ok", "type": "comment"}""",
                        status = HttpStatusCode.Created,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                path.endsWith("/search/metadata") && request.method == HttpMethod.Post -> {
                    respond(
                        content = """{
                            "assets": {
                                "items": [
                                    {"id": "pdf-1", "originalFileName": "sheet_drawing_book.pdf", "type": "OTHER"}
                                ],
                                "total": 1
                            }
                        }""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json")
                    )
                }
                path.endsWith("/assets/pdf-1/original") && request.method == HttpMethod.Get -> {
                    respond(
                        content = "%PDF-1.4 dummy pdf content",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/pdf")
                    )
                }
                else -> error("Unhandled mock endpoint: $path")
            }
        }

        ImmichClient(testConfig, engine = mockEngine).use { client ->
            val service = DrawingBookService(testConfig, client, AgyExecutor(testConfig), IppPrinterService(testConfig))
            service.pollOnce()
        }

        assertEquals(2, postedComments.size)
        assertTrue(postedComments[0].contains("Queued for printing! [ref: c-pdf]"))
        assertTrue(postedComments[1].contains("Printed successfully! [ref: c-pdf]"))
    }
}
