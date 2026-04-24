package com.velviagris.adventure.utils

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val LOG_DIR_NAME = "logs"
    private const val LOG_FILE_NAME = "adventure.log"
    private const val MAX_LOG_SIZE_BYTES = 512 * 1024L
    private const val TRIMMED_LOG_SIZE_BYTES = 384 * 1024L

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var exceptionHandlerInstalled = false

    private val fileLock = Any()
    private val timestampFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun initialize(context: Context) {
        appContext = context.applicationContext
        installExceptionHandler()
        i("AppLogger", "Logger initialized")
    }

    fun d(tag: String, message: String) = log(Log.DEBUG, tag, message, null)

    fun i(tag: String, message: String) = log(Log.INFO, tag, message, null)

    fun w(tag: String, message: String, throwable: Throwable? = null) = log(Log.WARN, tag, message, throwable)

    fun e(tag: String, message: String, throwable: Throwable? = null) = log(Log.ERROR, tag, message, throwable)

    fun exportLogs(contentResolver: ContentResolver, uri: Uri): Boolean {
        return try {
            val content = synchronized(fileLock) {
                val logFile = getLogFile()
                if (logFile != null && logFile.exists()) {
                    logFile.readText()
                } else {
                    buildMissingLogText()
                }
            }
            contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
                writer.write(content)
            } != null
        } catch (t: Throwable) {
            e("AppLogger", "Failed to export logs", t)
            false
        }
    }

    private fun log(priority: Int, tag: String, message: String, throwable: Throwable?) {
        Log.println(priority, tag, message)
        throwable?.let { Log.e(tag, message, it) }

        val line = buildString {
            append(timestampFormatter.format(Date()))
            append(" ")
            append(priorityToLabel(priority))
            append("/")
            append(tag)
            append(": ")
            append(message)
            if (throwable != null) {
                appendLine()
                append(stackTraceToString(throwable))
            }
            appendLine()
        }

        appendToFile(line)
    }

    private fun appendToFile(content: String) {
        synchronized(fileLock) {
            try {
                val logFile = getLogFile() ?: return
                logFile.parentFile?.mkdirs()
                trimLogIfNeeded(logFile)
                logFile.appendText(content)
            } catch (_: Throwable) {
                // Avoid recursive logging failures.
            }
        }
    }

    private fun trimLogIfNeeded(logFile: File) {
        if (!logFile.exists() || logFile.length() <= MAX_LOG_SIZE_BYTES) return

        val current = logFile.readText()
        if (current.length <= TRIMMED_LOG_SIZE_BYTES) return

        logFile.writeText(current.takeLast(TRIMMED_LOG_SIZE_BYTES.toInt()))
    }

    private fun installExceptionHandler() {
        if (exceptionHandlerInstalled) return

        synchronized(this) {
            if (exceptionHandlerInstalled) return

            val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                appendToFile(
                    buildString {
                        append(timestampFormatter.format(Date()))
                        append(" F/UncaughtException: Thread=")
                        append(thread.name)
                        appendLine()
                        append(stackTraceToString(throwable))
                        appendLine()
                    }
                )
                previousHandler?.uncaughtException(thread, throwable)
            }
            exceptionHandlerInstalled = true
        }
    }

    private fun getLogFile(): File? {
        val context = appContext ?: return null
        return File(File(context.filesDir, LOG_DIR_NAME), LOG_FILE_NAME)
    }

    private fun buildMissingLogText(): String {
        return "${timestampFormatter.format(Date())} I/AppLogger: No log file available yet.\n"
    }

    private fun stackTraceToString(throwable: Throwable): String {
        val stringWriter = StringWriter()
        val printWriter = PrintWriter(stringWriter)
        throwable.printStackTrace(printWriter)
        printWriter.flush()
        return stringWriter.toString()
    }

    private fun priorityToLabel(priority: Int): String {
        return when (priority) {
            Log.DEBUG -> "D"
            Log.INFO -> "I"
            Log.WARN -> "W"
            Log.ERROR -> "E"
            else -> priority.toString()
        }
    }
}
