package com.grezoo.viatommonitor

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

@SuppressLint("MissingPermission")
class BleService : Service() {

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): BleService = this@BleService
    }

    private var wakeLock: PowerManager.WakeLock? = null
    lateinit var csvLogger: CsvLogger
        private set

    private var bluetoothManager: BluetoothManager? = null
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothGatt: BluetoothGatt? = null

    // Preferences for remembering device
    private val prefs by lazy { getSharedPreferences("ViatomMonitorPrefs", Context.MODE_PRIVATE) }
    var savedDeviceAddress: String? = null
        private set
    var savedDeviceName: String? = null
        private set

    var isBleConnected: Boolean = false
        private set
    var isBleScanning: Boolean = false
        private set
    var connectedDeviceName: String = "Nincs csatlakoztatva"
        private set
    var connectedDeviceAddress: String = ""
        private set
    var bleStatusMsg: String = "CSIPESZ NINCS CSATLAKOZTATVA"
        private set

    // Discovered devices map during scanning (address -> DiscoveredOximeter)
    data class DiscoveredOximeter(
        val address: String,
        val name: String,
        val rssi: Int,
        val lastSeen: Long = System.currentTimeMillis()
    )
    val discoveredDevices = ConcurrentHashMap<String, DiscoveredOximeter>()

    // Live Metrics
    var currentSpo2: Int? = null
        private set
    var currentPulse: Int? = null
        private set
    var currentPi: Float? = null
        private set
    var isFingerIn: Boolean = false
        private set
    var lastWaveAmp: Int = 0
        private set
    var isBeatEvent: Boolean = false
        private set

    // Statistics
    var numbEpisodes: Int = 0
        private set
    var numbTotalSeconds: Int = 0
        private set
    private var inNumbEpisode: Boolean = false
    var lowestPi: Float = 100.0f
        private set
    var lowestPiTime: String = ""
        private set

    var apneaEpisodes: Int = 0
        private set
    private var inApneaEpisode: Boolean = false
    var lowestSpo2: Int = 100
        private set
    var lowestSpo2Time: String = ""
        private set
    private var spo2Baseline: Float = 97.5f

    // History for overnight strip
    val overnightHistory = Collections.synchronizedList(mutableListOf<JSONObject>())
    private val rawStreamBuffer = ByteArrayOutputStream()

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var liveTickJob: Job? = null
    private var scanTimeoutJob: Job? = null

    var onTelemetryListener: ((JSONObject) -> Unit)? = null

    companion object {
        const val CHANNEL_ID = "ViatomMonitorBleChannel"
        const val NOTIFICATION_ID = 101

        val NORDIC_UART_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NORDIC_UART_RX_CHAR: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val CLIENT_CONFIG_DESCRIPTOR: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

        private val COMPATIBLE_NAME_PATTERNS = listOf(
            "PC-60", "PC60", "PC_60", "POD", "VIATOM", "LEPU",
            "CREATIVE", "WELLUE", "O2", "PULSE", "OXIMETER", "SPO2", "PC-"
        )
    }

    override fun onCreate() {
        super.onCreate()
        csvLogger = CsvLogger(this)
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        // Load saved device from preferences
        savedDeviceAddress = prefs.getString("saved_mac", null)
        savedDeviceName = prefs.getString("saved_name", null)

        // WakeLock for reliable overnight monitoring
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ViatomMonitor::BleWakeLock"
        ).apply { setReferenceCounted(false) }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Készenlétben..."))

        startPeriodicTick()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        wakeLock?.acquire(10 * 3600 * 1000L) // 10 hours overnight lock
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        disconnectBle()
        if (wakeLock?.isHeld == true) wakeLock?.release()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Viatom Pulzoximéter orvosi telemetria adatfolyam"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_pulse)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // ==========================================
    // BLUETOOTH LE SCANNING & UNIVERSAL DISCOVERY
    // ==========================================
    fun toggleBle() {
        if (isBleConnected) {
            disconnectBle()
        } else if (isBleScanning) {
            stopScan()
            bleStatusMsg = "Keresés leállítva"
        } else {
            // If we have a saved device address, attempt fast direct reconnect
            val savedMac = savedDeviceAddress
            if (!savedMac.isNullOrEmpty()) {
                connectToAddress(savedMac, savedDeviceName ?: "Mentett Oximéter")
            } else {
                startScan()
            }
        }
    }

    fun startScan() {
        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            bleStatusMsg = "Bluetooth ki van kapcsolva a telefonon!"
            broadcastTickNow()
            return
        }

        disconnectBle()
        discoveredDevices.clear()
        isBleScanning = true
        bleStatusMsg = "Keresés a közelben (Viatom / Lepu / Creative)..."
        broadcastTickNow()

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            isBleScanning = false
            bleStatusMsg = "BLE kereső nem indítható el"
            broadcastTickNow()
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()

        // Match Nordic UART UUID or accept all for broad matching
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(NORDIC_UART_SERVICE)).build()
        )

        scanTimeoutJob?.cancel()
        scanTimeoutJob = serviceScope.launch {
            try {
                // First attempt: unfiltered scan to discover devices even if UUID is omitted in adv packet
                scanner.startScan(null, settings, scanCallback)
                
                // Allow up to 15 seconds for discovery
                delay(15000)
                if (isBleScanning && !isBleConnected) {
                    stopScan()
                    bleStatusMsg = if (discoveredDevices.isEmpty()) {
                        "Nem található oximéter. Kapcsolja be az ujján!"
                    } else {
                        "Válasszon eszközt a listából!"
                    }
                    broadcastTickNow()
                }
            } catch (e: Exception) {
                isBleScanning = false
                bleStatusMsg = "Keresési hiba: ${e.message}"
                broadcastTickNow()
            }
        }
    }

    fun stopScan() {
        scanTimeoutJob?.cancel()
        if (!isBleScanning) return
        isBleScanning = false
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
        broadcastTickNow()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val scanRecord = result.scanRecord
            val rawName = scanRecord?.deviceName ?: device.name ?: ""
            val address = device.address

            if (isCompatibleDevice(rawName, scanRecord)) {
                val displayName = if (rawName.isNotBlank()) rawName else "Oximéter ($address)"
                discoveredDevices[address] = DiscoveredOximeter(
                    address = address,
                    name = displayName,
                    rssi = result.rssi
                )

                // If this is our saved device, connect immediately!
                if (!savedDeviceAddress.isNullOrEmpty() && savedDeviceAddress.equals(address, ignoreCase = true)) {
                    stopScan()
                    connectToDevice(device, displayName)
                    return
                }

                // If exactly 1 device found after 2 seconds or if user clicked scan, broadcast update
                broadcastTickNow()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isBleScanning = false
            bleStatusMsg = "Keresés sikertelen (kód: $errorCode)"
            broadcastTickNow()
        }
    }

    private fun isCompatibleDevice(name: String, scanRecord: android.bluetooth.le.ScanRecord?): Boolean {
        val upperName = name.uppercase()
        val uuids = scanRecord?.serviceUuids?.map { it.uuid.toString().uppercase() } ?: emptyList()

        val hasUartUuid = uuids.any { it.contains("6E400001") || it.equals(NORDIC_UART_SERVICE.toString(), ignoreCase = true) }
        val hasMatchingName = COMPATIBLE_NAME_PATTERNS.any { upperName.contains(it) }

        return hasUartUuid || hasMatchingName
    }

    fun connectToAddress(macAddress: String, explicitName: String? = null) {
        stopScan()
        val adapter = bluetoothAdapter ?: return
        try {
            val device = adapter.getRemoteDevice(macAddress)
            val name = explicitName ?: discoveredDevices[macAddress]?.name ?: device.name ?: "Oximéter ($macAddress)"
            connectToDevice(device, name)
        } catch (e: Exception) {
            bleStatusMsg = "Csatlakozási hiba: ${e.message}"
            broadcastTickNow()
        }
    }

    private fun connectToDevice(device: BluetoothDevice, name: String) {
        stopScan()
        bleStatusMsg = "Csatlakozás: $name..."
        connectedDeviceName = name
        connectedDeviceAddress = device.address
        broadcastTickNow()

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false, gattCallback)
        }
    }

    fun forgetDevice() {
        savedDeviceAddress = null
        savedDeviceName = null
        prefs.edit().remove("saved_mac").remove("saved_name").apply()
        disconnectBle()
        bleStatusMsg = "Eszköz elfelejtve. Új keresés indítható."
        broadcastTickNow()
    }

    fun disconnectBle() {
        stopScan()
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
        } catch (_: Exception) {}
        bluetoothGatt = null
        isBleConnected = false
        isBleScanning = false
        isFingerIn = false
        currentSpo2 = null
        currentPulse = null
        currentPi = null
        connectedDeviceAddress = ""
        bleStatusMsg = "CSIPESZ LEKAPCSOLVA"
        updateNotification("Készenlétben (levéve)")
        broadcastTickNow()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isBleConnected = true
                isBleScanning = false
                bleStatusMsg = "Csatlakoztatva: $connectedDeviceName"
                updateNotification("Csatlakoztatva: $connectedDeviceName")

                // Save this successful connection to preferences for future instant auto-connect
                gatt?.device?.let { dev ->
                    savedDeviceAddress = dev.address
                    savedDeviceName = connectedDeviceName
                    prefs.edit()
                        .putString("saved_mac", dev.address)
                        .putString("saved_name", connectedDeviceName)
                        .apply()
                }

                broadcastTickNow()
                gatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isBleConnected = false
                isFingerIn = false
                currentSpo2 = null
                currentPulse = null
                currentPi = null
                bleStatusMsg = "Kapcsolat bontva"
                updateNotification("Kapcsolat bontva")
                broadcastTickNow()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) return

            // Search for Nordic UART Service or any service containing RX notify characteristic
            var rxChar: BluetoothGattCharacteristic? = null
            for (service in gatt.services) {
                for (characteristic in service.characteristics) {
                    val uuidStr = characteristic.uuid.toString().uppercase()
                    if (uuidStr.contains("6E400003") || characteristic.uuid == NORDIC_UART_RX_CHAR) {
                        rxChar = characteristic
                        break
                    }
                }
                if (rxChar != null) break
            }

            if (rxChar != null) {
                gatt.setCharacteristicNotification(rxChar, true)
                val descriptor = rxChar.getDescriptor(CLIENT_CONFIG_DESCRIPTOR)
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(descriptor)
                    }
                }
            }
        }

        // Modern Android 13+ (API 33+) overload
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            processBleStream(value)
        }

        // Legacy Android 12 and below overload
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            val bytes = characteristic?.value ?: return
            processBleStream(bytes)
        }
    }

    // ==========================================
    // PROTOKOL ELEMZŐ (VIATOM 0xAA, 0x55 STREAM)
    // ==========================================
    @Synchronized
    private fun processBleStream(chunk: ByteArray) {
        rawStreamBuffer.write(chunk)
        var buf = rawStreamBuffer.toByteArray()

        while (buf.size >= 4) {
            // Fejléc keresése (0xAA 0x55)
            var idx = -1
            for (i in 0 until buf.size - 1) {
                if ((buf[i].toInt() and 0xFF) == 0xAA && (buf[i + 1].toInt() and 0xFF) == 0x55) {
                    idx = i
                    break
                }
            }

            if (idx < 0) {
                rawStreamBuffer.reset()
                if (buf.isNotEmpty()) rawStreamBuffer.write(buf, buf.size - 1, 1)
                break
            }

            if (idx > 0) {
                val trimmed = buf.copyOfRange(idx, buf.size)
                rawStreamBuffer.reset()
                rawStreamBuffer.write(trimmed)
                buf = trimmed
            }

            if (buf.size < 4) break

            val token = buf[2].toInt() and 0xFF
            val length = buf[3].toInt() and 0xFF
            val packetLen = 4 + length

            if (buf.size < packetLen) break // Várjuk a csomag végét

            val pkt = buf.copyOfRange(0, packetLen)
            val remainder = buf.copyOfRange(packetLen, buf.size)
            rawStreamBuffer.reset()
            rawStreamBuffer.write(remainder)
            buf = remainder

            // 0x0F: Telemetria Csomag
            if (token == 0x0F && pkt.size >= 5) {
                val subId = pkt[4].toInt() and 0xFF

                // SUB-ID 0x01: Numerikus Értékek (SpO2, Pulzus, PI)
                if (subId == 0x01 && pkt.size >= 7) {
                    val s = pkt[5].toInt() and 0xFF
                    val p = pkt[6].toInt() and 0xFF
                    val piByte = if (pkt.size > 8) (pkt[8].toInt() and 0xFF) else 0xFF
                    val pIndex = if (piByte != 0xFF && piByte > 0) (piByte / 10.0f) else 0.0f

                    // Csipesz ujj érzékelés: ha nincs ujj a csipeszben, 0xFF vagy 0 érkezik
                    val finger = !(s == 0xFF || s == 0 || p == 0xFF || p == 0)
                    isFingerIn = finger

                    if (finger) {
                        currentSpo2 = if (s in 35..100) s else null
                        currentPulse = if (p in 25..250) p else null
                        currentPi = pIndex

                        // Apnoé feldolgozás (AASM)
                        currentSpo2?.let { spo2Val ->
                            if (spo2Val < lowestSpo2) {
                                lowestSpo2 = spo2Val
                                lowestSpo2Time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                            }
                            val drop = spo2Baseline - spo2Val
                            if (drop >= 3.0f || spo2Val < 91) {
                                if (!inApneaEpisode) {
                                    inApneaEpisode = true
                                    apneaEpisodes++
                                }
                            } else if (drop < 1.5f && spo2Val >= 93) {
                                inApneaEpisode = false
                            }
                            spo2Baseline = spo2Baseline * 0.995f + spo2Val * 0.005f
                        }

                        // Zsibbadás és Keringés (PI < 0.8%)
                        currentPi?.let { piVal ->
                            if (piVal < lowestPi) {
                                lowestPi = piVal
                                lowestPiTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                            }
                            if (piVal < 0.8f) {
                                numbTotalSeconds++
                                if (!inNumbEpisode) {
                                    inNumbEpisode = true
                                    numbEpisodes++
                                }
                            } else if (piVal >= 1.0f) {
                                inNumbEpisode = false
                            }
                        }

                        // CSV naplózás másodpercenként
                        csvLogger.logTelemetry(
                            spo2 = currentSpo2,
                            pulse = currentPulse,
                            pi = currentPi,
                            fingerIn = true,
                            isApnea = inApneaEpisode,
                            isNumb = inNumbEpisode
                        )

                        updateNotification("SpO2: $currentSpo2% | Pulzus: ${currentPulse}bpm | PI: ${currentPi}%")
                    } else {
                        currentSpo2 = null
                        currentPulse = null
                        currentPi = null
                        updateNotification("Ujj kihúzva a csipeszből")
                    }
                }
                // SUB-ID 0x02 vagy 0x03: Hullámforma Minták (PPG)
                else if ((subId == 0x02 || subId == 0x03) && pkt.size >= 6) {
                    if (subId == 0x02 && pkt.size > 6) {
                        for (i in 5 until pkt.size - 1) {
                            val rawS = pkt[i].toInt() and 0xFF
                            val isBeat = (rawS and 0x80) != 0
                            val amp = rawS and 0x7F
                            val waveJson = JSONObject().apply {
                                put("type", "wave")
                                put("wave", amp)
                                put("beat", isBeat)
                            }
                            onTelemetryListener?.invoke(waveJson)
                        }
                    } else {
                        lastWaveAmp = pkt[5].toInt() and 0xFF
                        isBeatEvent = (lastWaveAmp and 0x80) != 0
                        lastWaveAmp = lastWaveAmp and 0x7F

                        val waveJson = JSONObject().apply {
                            put("type", "wave")
                            put("wave", lastWaveAmp)
                            put("beat", isBeatEvent)
                        }
                        onTelemetryListener?.invoke(waveJson)
                    }
                }
            }
        }
    }

    private fun startPeriodicTick() {
        liveTickJob?.cancel()
        liveTickJob = serviceScope.launch {
            while (isActive) {
                delay(1000)
                broadcastTickNow()
            }
        }
    }

    private fun broadcastTickNow() {
        val payload = buildLivePayload()
        onTelemetryListener?.invoke(payload)
    }

    fun buildLivePayload(): JSONObject {
        val json = JSONObject()
        json.put("type", "live")
        json.put("ble_connected", isBleConnected)
        json.put("ble_scanning", isBleScanning)
        json.put("device_name", connectedDeviceName)
        json.put("device_address", connectedDeviceAddress)
        json.put("ble_status_msg", bleStatusMsg)
        json.put("saved_device_name", savedDeviceName ?: "")
        json.put("saved_device_address", savedDeviceAddress ?: "")

        json.put("spo2", currentSpo2 ?: JSONObject.NULL)
        json.put("pulse", currentPulse ?: JSONObject.NULL)
        json.put("pi", if (currentPi != null) (currentPi!! * 10).roundToInt() / 10.0 else JSONObject.NULL)
        json.put("finger_in", isFingerIn)

        json.put("recording", csvLogger.isRecording)
        json.put("record_seconds", csvLogger.recordSeconds)
        json.put("current_csv_file", csvLogger.currentCsvFile?.name ?: "")

        // Statisztikák
        json.put("numb_episodes", numbEpisodes)
        json.put("numb_total_seconds", numbTotalSeconds)
        json.put("lowest_pi", if (lowestPi < 100) (lowestPi * 10).roundToInt() / 10.0 else JSONObject.NULL)
        json.put("lowest_pi_time", lowestPiTime)

        json.put("apnea_episodes", apneaEpisodes)
        json.put("lowest_spo2", if (lowestSpo2 < 100) lowestSpo2 else JSONObject.NULL)
        json.put("lowest_spo2_time", lowestSpo2Time)

        // Környezetben talált eszközök listája
        val devArr = JSONArray()
        for ((_, dev) in discoveredDevices) {
            devArr.put(JSONObject().apply {
                put("address", dev.address)
                put("name", dev.name)
                put("rssi", dev.rssi)
            })
        }
        json.put("discovered_devices", devArr)

        return json
    }

    fun getHistoryPayload(): JSONObject {
        val json = buildLivePayload()
        val arr = JSONArray()
        synchronized(overnightHistory) {
            for (item in overnightHistory) {
                arr.put(item)
            }
        }
        json.put("history", arr)
        return json
    }
}
