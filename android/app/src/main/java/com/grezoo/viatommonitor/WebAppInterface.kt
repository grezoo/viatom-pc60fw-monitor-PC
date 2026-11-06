package com.grezoo.viatommonitor

import android.content.Context
import android.content.Intent
import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONObject

class WebAppInterface(
    private val context: Context,
    private val serviceProvider: () -> BleService?
) {

    @JavascriptInterface
    fun isAndroid(): Boolean = true

    @JavascriptInterface
    fun toggleBle() {
        serviceProvider()?.toggleBle()
    }

    @JavascriptInterface
    fun startScan() {
        serviceProvider()?.startScan()
    }

    @JavascriptInterface
    fun stopScan() {
        serviceProvider()?.stopScan()
    }

    @JavascriptInterface
    fun connectDevice(mac: String) {
        serviceProvider()?.connectToAddress(mac)
    }

    @JavascriptInterface
    fun forgetDevice() {
        serviceProvider()?.forgetDevice()
    }

    @JavascriptInterface
    fun toggleRec(): String {
        val service = serviceProvider() ?: return "{}"
        val logger = service.csvLogger
        if (logger.isRecording) {
            val file = logger.stopRecording()
            return JSONObject().apply {
                put("recording", false)
                put("fileName", file?.name ?: "")
            }.toString()
        } else {
            val file = logger.startRecording()
            return JSONObject().apply {
                put("recording", true)
                put("fileName", file.name)
            }.toString()
        }
    }

    @JavascriptInterface
    fun listSessions(): String {
        val service = serviceProvider() ?: return "[]"
        val list = service.csvLogger.listSessions()
        val arr = JSONArray()
        for (item in list) {
            arr.put(JSONObject().apply {
                put("fileName", item.fileName)
                put("sizeBytes", item.sizeBytes)
                put("lastModified", item.lastModified)
                put("formattedDate", item.formattedDate)
            })
        }
        return arr.toString()
    }

    @JavascriptInterface
    fun shareSession(fileName: String) {
        val service = serviceProvider() ?: return
        val uri = service.csvLogger.getFileUri(fileName) ?: return

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Viatom PC-60FW Éjszakai Mérés: $fileName")
            putExtra(Intent.EXTRA_TEXT, "Csatolva a Viatom PC-60FW orvosi mérési naplója ($fileName).")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(shareIntent, "Mérési napló (CSV) megosztása").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    @JavascriptInterface
    fun shareLatestOrCurrentCsv() {
        val service = serviceProvider() ?: return
        val currentFile = service.csvLogger.currentCsvFile?.name
        val latestSaved = service.csvLogger.listSessions().firstOrNull()?.fileName
        val target = currentFile ?: latestSaved
        if (target != null) {
            shareSession(target)
        }
    }

    @JavascriptInterface
    fun loadSessionCsv(fileName: String): String {
        val service = serviceProvider() ?: return ""
        return service.csvLogger.getSessionCsv(fileName) ?: ""
    }
}
