package com.github.stepanort.colorich

import com.github.stepanort.colorich.agy.AgyExecutor
import com.github.stepanort.colorich.config.AppConfig
import com.github.stepanort.colorich.immich.ImmichClient
import com.github.stepanort.colorich.printer.IppPrinterService
import com.github.stepanort.colorich.service.DrawingBookService
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.system.exitProcess

private val logger = LoggerFactory.getLogger("com.github.stepanort.colorich.Main")

fun main() {
    logger.info("Starting Colorich Service (Immich Coloring Book & Print Daemon)...")

    val config = try {
        AppConfig.load()
    } catch (e: Exception) {
        logger.error("Configuration error: ${e.message}", e)
        exitProcess(1)
    }

    logger.info("Configuration loaded:")
    logger.info("  Immich Server: ${config.immichServerUrl}")
    logger.info("  Shared Album ID: ${config.sharedAlbumId}")
    logger.info("  CUPS Server: ${config.cupsServer}")
    logger.info("  Printer Queue: ${config.printerName ?: "(auto-detect)"}")
    logger.info("  Poll Interval: ${config.pollIntervalSeconds}s")
    logger.info("  Dry Run: ${config.dryRun}")

    val immichClient = ImmichClient(config)
    val agyExecutor = AgyExecutor(config, workspaceDir = File("."))
    val printerService = IppPrinterService(config)

    val service = DrawingBookService(
        config = config,
        immichClient = immichClient,
        agyExecutor = agyExecutor,
        printerService = printerService
    )

    runBlocking {
        val job = launch {
            service.startPollingLoop(this)
        }

        Runtime.getRuntime().addShutdownHook(Thread {
            logger.info("Shutting down service...")
            job.cancel()
            immichClient.close()
        })

        job.join()
    }
}
