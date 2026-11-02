package com.github.stepanort.colorich.agy

import com.github.stepanort.colorich.config.AppConfig
import com.kgit2.kommand.process.Command
import com.kgit2.kommand.process.Stdio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.File

class AgyExecutor(
    private val config: AppConfig,
    private val workspaceDir: File = File(".")
) {
    private val logger = LoggerFactory.getLogger(AgyExecutor::class.java)

    suspend fun convertPhotoToColoringPagePng(
        inputImageFile: File,
        outputPngFile: File
    ): File = withContext(Dispatchers.IO) {
        logger.info("Executing agy to convert ${inputImageFile.name} to A4 PNG ${outputPngFile.name}")

        val prompt = buildString {
            append("Follow skill 'photo-to-coloring-page' to turn @[${inputImageFile.absolutePath}] into a children's coloring book page for a 4-year-old child. ")
            append("1. Crop to the main subject with breathing room for context. ")
            append("2. Delegate line-art generation to the 'image-generator' subagent. ")
            append("3. Once the illustration is generated, do NOT perform iterative multi-turn manual pixel-cleaning or flood-fill retouching. Proceed directly through steps 3 and 4 in a single Python script. ")
            append("4. Layout the illustration on a portrait A4 canvas (2480x3508 at 300 DPI) with margins and an 8px solid black border frame. ")
            append("5. Export the final drawing book PNG to: ${outputPngFile.absolutePath}")
        }

        val command = Command(config.agyPath)
            .args(
                "-p", prompt,
                "--dangerously-skip-permissions",
                "--add-dir", workspaceDir.absolutePath
            )
            .stdout(Stdio.Pipe)
            .stderr(Stdio.Pipe)

        val child = command.spawn()

        // Drain stdout and stderr concurrently so the child never deadlocks on a full OS pipe buffer
        val stdoutDeferred = async(Dispatchers.IO) {
            child.bufferedStdout()?.lines()?.joinToString("\n").orEmpty()
        }
        val stderrDeferred = async(Dispatchers.IO) {
            child.bufferedStderr()?.lines()?.joinToString("\n").orEmpty()
        }

        val timeoutMs = config.agyTimeoutSeconds * 1000L
        val exitCode = withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.IO) { child.wait() }
        } ?: run {
            runCatching { child.kill() }
            throw IllegalStateException("agy timed out after ${config.agyTimeoutSeconds}s")
        }

        val stdout = stdoutDeferred.await().trim()
        val stderr = stderrDeferred.await().trim()

        if (stdout.isNotBlank()) {
            logger.info("agy stdout:\n$stdout")
        }
        if (stderr.isNotBlank()) {
            logger.warn("agy stderr:\n$stderr")
        }

        if (exitCode != 0) {
            throw IllegalStateException("agy failed with exit code $exitCode: $stderr")
        }

        if (!outputPngFile.exists() || outputPngFile.length() == 0L) {
            // Check if agy saved it in the current directory or nearby
            val fallback = File(inputImageFile.parentFile, outputPngFile.name)
            if (fallback.exists() && fallback.length() > 0) {
                fallback.copyTo(outputPngFile, overwrite = true)
            } else {
                throw IllegalStateException("agy succeeded but expected output PNG not found at ${outputPngFile.absolutePath}")
            }
        }

        logger.info("Successfully produced A4 PNG at ${outputPngFile.absolutePath} (size: ${outputPngFile.length()} bytes)")
        outputPngFile
    }
}
