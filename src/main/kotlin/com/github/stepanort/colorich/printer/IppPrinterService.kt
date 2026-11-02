package com.github.stepanort.colorich.printer

import com.github.stepanort.colorich.config.AppConfig
import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.File

class IppPrinterService(
    private val config: AppConfig
) {
    private val logger = LoggerFactory.getLogger(IppPrinterService::class.java)

    suspend fun printImage(imageFile: File) = withContext(Dispatchers.IO) {
        if (!imageFile.exists() || imageFile.length() == 0L) {
            throw IllegalArgumentException("Image file to print does not exist or is empty: ${imageFile.absolutePath}")
        }

        if (imageFile.extension.equals("pdf", ignoreCase = true)) {
            printPdf(imageFile)
            return@withContext
        }

        logger.info("Converting image ${imageFile.name} to printable A4 PDF before dispatching to printer")
        val tempPdf = File(config.tempDir, "${imageFile.nameWithoutExtension}_print.pdf")
        try {
            val script = "import sys; from PIL import Image; im = Image.open(sys.argv[1]).convert('RGB'); im.save(sys.argv[2], 'PDF', resolution=300.0)"
            val cmd = Command("python3")
                .args("-c", script, imageFile.absolutePath, tempPdf.absolutePath)
                .stdout(Stdio.Pipe)
                .stderr(Stdio.Pipe)
            val (_, _, stderr) = runCommandWithTimeout(cmd, timeoutMs = 60_000L, label = "python3 PDF conversion")

            if (!tempPdf.exists() || tempPdf.length() == 0L) {
                throw IllegalStateException("Failed to convert image to PDF for printing: $stderr")
            }

            printPdf(tempPdf)
        } finally {
            if (tempPdf.exists()) {
                tempPdf.delete()
            }
        }
    }

    suspend fun printPdf(pdfFile: File) = withContext(Dispatchers.IO) {
        if (!pdfFile.exists() || pdfFile.length() == 0L) {
            throw IllegalArgumentException("PDF file to print does not exist or is empty: ${pdfFile.absolutePath}")
        }

        val destination = resolvePrinterDestination()

        if (config.dryRun) {
            logger.info("DRY_RUN enabled: skipping dispatch of ${pdfFile.name} to printer queue '$destination'")
            return@withContext
        }

        logger.info("Dispatching PDF ${pdfFile.name} to printer queue '$destination' via CUPS server '${config.cupsServer}'")

        val command = Command("lp")
            .args(
                "-h", config.cupsServer,
                "-d", destination,
                "-o", "media=A4",
                "-o", "fit-to-page",
                pdfFile.absolutePath
            )
            .stdout(Stdio.Pipe)
            .stderr(Stdio.Pipe)

        val (_, stdout, _) = runCommandWithTimeout(command, timeoutMs = 60_000L, label = "lp")
        logger.info("Print job successfully accepted by printer: $stdout")
    }

    private suspend fun resolvePrinterDestination(): String {
        if (!config.printerName.isNullOrBlank()) {
            return config.printerName
        }

        // Try to auto-detect printer from CUPS server
        return try {
            val checkCmd = Command("lpstat")
                .args("-h", config.cupsServer, "-p")
                .stdout(Stdio.Pipe)
                .stderr(Stdio.Pipe)
            val (_, stdout, _) = runCommandWithTimeout(checkCmd, timeoutMs = 10_000L, label = "lpstat", failOnNonZero = false)
            // Parse "printer <name> is ..."
            val regex = Regex("""printer\s+([^\s]+)\s+is""")
            val match = regex.find(stdout)
            if (match != null) {
                val detected = match.groupValues[1]
                logger.info("Auto-detected active CUPS printer: $detected")
                detected
            } else {
                logger.warn("Could not parse printer from lpstat output: $stdout. Falling back to 'default'")
                "default"
            }
        } catch (e: Exception) {
            logger.warn("Failed to auto-detect printer destination via lpstat: ${e.message}. Falling back to 'default'")
            "default"
        }
    }

    private suspend fun runCommandWithTimeout(
        command: Command,
        timeoutMs: Long,
        label: String,
        failOnNonZero: Boolean = true
    ): Triple<Int, String, String> = withContext(Dispatchers.IO) {
        val child = command.spawn()
        val stdoutDeferred = async(Dispatchers.IO) {
            child.bufferedStdout()?.lines()?.joinToString("\n").orEmpty()
        }
        val stderrDeferred = async(Dispatchers.IO) {
            child.bufferedStderr()?.lines()?.joinToString("\n").orEmpty()
        }

        val exitCode = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) { child.wait() }
        } ?: run {
            runCatching { child.kill() }
            throw IllegalStateException("$label timed out after ${timeoutMs / 1000}s")
        }

        val stdout = stdoutDeferred.await().trim()
        val stderr = stderrDeferred.await().trim()

        if (failOnNonZero && exitCode != 0) {
            logger.error("$label failed (exit code $exitCode): $stderr")
            throw IllegalStateException("$label failed ($exitCode): $stderr")
        }

        Triple(exitCode, stdout, stderr)
    }
}
