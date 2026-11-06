package com.grezoo.viatommonitor

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CsvLogger(private val context: Context) {

    private val logsDir: File by lazy {
        File(context.filesDir, "logs").apply { if (!exists()) mkdirs() }
    }

    private var currentFile: File? = null
    val currentCsvFile: File?
        get() = currentFile
    private var writer: FileWriter? = null
    var isRecording: Boolean = false
        private set

    var recordSeconds: Long = 0
        private set

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    private val fileDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    @Synchronized
    fun startRecording(customName: String? = null): File {
        stopRecording()
        val timestamp = fileDateFormat.format(Date())
        val fileName = if (customName.isNullOrBlank()) "meres_$timestamp.csv" else "$customName.csv"
        val file = File(logsDir, fileName)

        val isNew = !file.exists()
        writer = FileWriter(file, true)
        if (isNew) {
            writer?.append("Időbélyeg,SpO2 (%),Pulzus (bpm),PI (%),Állapot,Apnoé Esés,Aritmia\n")
            writer?.flush()
        }

        currentFile = file
        isRecording = true
        recordSeconds = 0
        return file
    }

    @Synchronized
    fun logSecond(
        spo2: Int?,
        pulse: Int?,
        pi: Float?,
        status: String,
        isApnea: Boolean,
        isArrhythmia: Boolean
    ) {
        if (!isRecording || writer == null) return

        recordSeconds++
        val nowStr = timeFormat.format(Date())
        val spo2Str = spo2?.toString() ?: ""
        val pulseStr = pulse?.toString() ?: ""
        val piStr = pi?.let { String.format(Locale.US, "%.1f", it) } ?: ""
        val apneaStr = if (isApnea) "1" else "0"
        val arrStr = if (isArrhythmia) "1" else "0"

        writer?.append("$nowStr,$spo2Str,$pulseStr,$piStr,$status,$apneaStr,$arrStr\n")
        writer?.flush()
    }

    @Synchronized
    fun logTelemetry(
        spo2: Int?,
        pulse: Int?,
        pi: Float?,
        fingerIn: Boolean,
        isApnea: Boolean,
        isNumb: Boolean,
        isArrhythmia: Boolean = false
    ) {
        val status = if (fingerIn) "UJJ_A_CSIPESZBEN" else "CSIPESZ_LEVÉVE"
        logSecond(spo2, pulse, pi, status, isApnea, isArrhythmia)
    }

    @Synchronized
    fun stopRecording(): File? {
        if (!isRecording) return null
        val finished = currentFile
        try {
            writer?.flush()
            writer?.close()
        } catch (_: Exception) {}
        writer = null
        isRecording = false
        return finished
    }

    fun listSessions(): List<SessionInfo> {
        return logsDir.listFiles { file -> file.extension.equals("csv", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.map { f ->
                SessionInfo(
                    fileName = f.name,
                    sizeBytes = f.length(),
                    lastModified = f.lastModified(),
                    formattedDate = timeFormat.format(Date(f.lastModified()))
                )
            } ?: emptyList()
    }

    fun getSessionCsv(fileName: String): String? {
        val file = File(logsDir, fileName)
        return if (file.exists()) file.readText() else null
    }

    fun getFileUri(fileName: String): Uri? {
        val file = File(logsDir, fileName)
        return if (file.exists()) {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } else null
    }

    data class SessionInfo(
        val fileName: String,
        val sizeBytes: Long,
        val lastModified: Long,
        val formattedDate: String
    )
}
