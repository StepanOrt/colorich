package com.github.stepanort.colorich.config

import io.github.cdimascio.dotenv.Dotenv
import java.io.File

data class AppConfig(
    val immichServerUrl: String,
    val immichApiKey: String,
    val sharedAlbumId: String,
    val printerName: String? = null,
    val cupsServer: String = "/run/cups/cups.sock",
    val pollIntervalSeconds: Long = 60L,
    val agyPath: String = "agy",
    val agyTimeoutSeconds: Long = 600L,
    val dryRun: Boolean = false,
    val tempDir: File = File(System.getProperty("java.io.tmpdir"), "immich-drawingbook")
) {
    init {
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
    }

    companion object {
        fun load(): AppConfig {
            val dotenv = try {
                Dotenv.configure().ignoreIfMissing().load()
            } catch (e: Exception) {
                null
            }

            fun get(key: String, default: String? = null): String {
                val envVal = System.getenv(key)
                if (!envVal.isNullOrBlank()) return envVal
                val dotVal = dotenv?.get(key)
                if (!dotVal.isNullOrBlank()) return dotVal
                return default ?: throw IllegalArgumentException("Missing required configuration parameter: $key")
            }

            fun getOptional(key: String, default: String): String {
                val envVal = System.getenv(key)
                if (!envVal.isNullOrBlank()) return envVal
                val dotVal = dotenv?.get(key)
                if (!dotVal.isNullOrBlank()) return dotVal
                return default
            }

            val rawServerUrl = get("IMMICH_SERVER_URL").trim().trimEnd('/')
            val serverUrl = if (rawServerUrl.endsWith("/api")) rawServerUrl.removeSuffix("/api") else rawServerUrl

            val printerNameRaw = getOptional("PRINTER_NAME", "")
            val printerName = if (printerNameRaw.isNotBlank()) printerNameRaw else null
            val cupsServer = getOptional("CUPS_SERVER", "/run/cups/cups.sock")

            return AppConfig(
                immichServerUrl = serverUrl,
                immichApiKey = get("IMMICH_API_KEY"),
                sharedAlbumId = get("IMMICH_SHARED_ALBUM_ID"),
                printerName = printerName,
                cupsServer = cupsServer,
                pollIntervalSeconds = getOptional("POLL_INTERVAL_SECONDS", "60").toLongOrNull() ?: 60L,
                agyPath = getOptional("AGY_PATH", "agy"),
                agyTimeoutSeconds = getOptional("AGY_TIMEOUT_SECONDS", "600").toLongOrNull() ?: 600L,
                dryRun = getOptional("DRY_RUN", "false").toBoolean()
            )
        }
    }
}
