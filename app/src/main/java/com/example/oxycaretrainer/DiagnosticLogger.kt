package com.example.oxycaretrainer

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLogger {
    private const val MAX_FILE_BYTES = 512 * 1024L
    private const val MAX_BACKUPS = 3
    private var directory: File? = null

    @Synchronized
    fun initialize(context: Context) {
        if (directory != null) return
        directory = File(context.filesDir, "diagnostics").apply { mkdirs() }
        info("logger_initialized", "process=${android.os.Process.myPid()}")
    }

    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            DiagnosticLogger.error("uncaught_exception", throwable, "thread=${thread.name}")
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun info(event: String, details: String = "") = write("INFO", event, details, null)

    fun warn(event: String, details: String = "") = write("WARN", event, details, null)

    fun error(event: String, throwable: Throwable, details: String = "") =
        write("ERROR", event, details, throwable)

    @Synchronized
    fun createExportFile(context: Context): File {
        val exportDirectory = File(context.cacheDir, "diagnostics").apply { mkdirs() }
        val target = File(exportDirectory, "oxycare-diagnostics.txt")
        val sourceDirectory = directory ?: File(context.filesDir, "diagnostics")
        val files = (MAX_BACKUPS downTo 1).map { File(sourceDirectory, "oxycare.log.$it") } +
            File(sourceDirectory, "oxycare.log")
        target.outputStream().bufferedWriter().use { writer ->
            files.filter { it.exists() }.forEach { file ->
                writer.appendLine("===== ${file.name} =====")
                file.forEachLine { writer.appendLine(it) }
            }
        }
        return target
    }

    @Synchronized
    private fun write(level: String, event: String, details: String, throwable: Throwable?) {
        val dir = directory ?: return
        runCatching {
            rotateIfNeeded(dir)
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val message = buildString {
                append(timestamp).append(' ').append(level).append(' ').append(event)
                if (details.isNotBlank()) append(" | ").append(details.replace('\n', ' '))
                if (throwable != null) {
                    append(" | ").append(throwable::class.java.simpleName).append(": ")
                    append(throwable.message.orEmpty().replace('\n', ' '))
                    append(" | ").append(throwable.stackTraceToString().replace('\n', ' '))
                }
                append('\n')
            }
            File(dir, "oxycare.log").appendText(message)
        }
    }

    private fun rotateIfNeeded(dir: File) {
        val current = File(dir, "oxycare.log")
        if (!current.exists() || current.length() < MAX_FILE_BYTES) return
        File(dir, "oxycare.log.$MAX_BACKUPS").delete()
        for (index in MAX_BACKUPS - 1 downTo 1) {
            val source = File(dir, "oxycare.log.$index")
            if (source.exists()) source.renameTo(File(dir, "oxycare.log.${index + 1}"))
        }
        current.renameTo(File(dir, "oxycare.log.1"))
    }
}
