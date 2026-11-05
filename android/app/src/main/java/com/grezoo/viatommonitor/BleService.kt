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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.*
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

    var isBleConnected: Boolean = false
        private set
    var isBleScanning: Boolean = false
        private set
    var connectedDeviceName: String = "Nincs csatlakoztatva"
        private set
    var bleStatusMsg: String = "CSIPESZ NINCS CSATLAKOZTATVA"
        private set

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

    var onTelemetryListener: ((JSONObject) -> Unit)? = null

    companion object {
        const val CHANNEL_ID = "ViatomMonitorBleChannel"
        const val NOTIFICATION_ID = 101

        val NORDIC_UART_SERVICE = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NORDIC_UART_RX_CHAR = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val CLIENT_CONFIG_DESCRIPTOR = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }

    override fun onCreate() {
        super.onCreate()
        csvLogger = CsvLogger(this)
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

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
        wakeLock?.acquire(8 * 3600 * 1000L) // 8 hours lock
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
                description = "Folyamatos pulzoximéter adatgyűjtés és riasztási felügyelet"
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
    // BLUETOOTH LE SCANNING & CONNECTION
    // ==========================================
    fun toggleBle() {
        if (isBleConnected || isBleScanning) {
            disconnectBle()
        } else {
            startScan()
        }
    }

    fun startScan() {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            bleStatusMsg = "Bluetooth ki van kapcsolva a telefonon!"
            return
        }

        disconnectBle()
        isBleScanning = true
        bleStatusMsg = "Keresés a közelben (PC-60FW)..."

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            isBleScanning = false
            bleStatusMsg = "Nem sikerült elindítani a BLE keresőt"
            return
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        serviceScope.launch {
            try {
                scanner.startScan(null, settings, scanCallback)
                delay(12000) // 12 seconds scan timeout
                if (isBleScanning && !isBleConnected) {
                    stopScan()
                    bleStatusMsg = "Időtúllépés: csipesz nem található. Kapcsolja be!"
                }
            } catch (e: Exception) {
                isBleScanning = false
                bleStatusMsg = "Keresési hiba: ${e.message}"
            }
        }
    }

    private fun stopScan() {
        if (!isBleScanning) return
        isBleScanning = false
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {}
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val device = result?.device ?: return
            val name = result.scanRecord?.deviceName ?: device.name ?: ""

            // PC-60FW, Lepu, Creative Medical, Pod szűrés
            val isTarget = name.contains("PC-60FW", ignoreCase = true) ||
                    name.contains("PC_60FW", ignoreCase = true) ||
                    name.contains("Pod", ignoreCase = true) ||
                    name.contains("Viatom", ignoreCase = true) ||
                    name.contains("Lepu", ignoreCase = true)

            if (isTarget) {
                stopScan()
                connectToDevice(device, name)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isBleScanning = false
            bleStatusMsg = "Keresés sikertelen (kód: $errorCode)"
        }
    }

    private fun connectToDevice(device: BluetoothDevice, name: String) {
        bleStatusMsg = "Csatlakozás: $name..."
        connectedDeviceName = name

        bluetoothGatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(this, false, gattCallback)
        }
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
        bleStatusMsg = "CSIPESZ LEKAPCSOLVA"
        updateNotification("Készenlétben (levéve)")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                isBleConnected = true
                isBleScanning = false
                bleStatusMsg = "Csatlakoztatva: $connectedDeviceName"
                updateNotification("Csatlakoztatva: $connectedDeviceName")
                gatt?.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                isBleConnected = false
                isFingerIn = false
                currentSpo2 = null
                currentPulse = null
                currentPi = null
                bleStatusMsg = "Kapcsolat megszakadt"
                updateNotification("Kapcsolat megszakadt")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) return

            val service = gatt.getService(NORDIC_UART_SERVICE)
            val rxChar = service?.getCharacteristic(NORDIC_UART_RX_CHAR)
            if (rxChar != null) {
                gatt.setCharacteristicNotification(rxChar, true)
                val descriptor = rxChar.getDescriptor(CLIENT_CONFIG_DESCRIPTOR)
                if (descriptor != null) {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
            }
        }

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
                // Nincs fejléc, megtartjuk az utolsó bájtot
                rawStreamBuffer.reset()
                if (buf.isNotEmpty()) rawStreamBuffer.write(buf, buf.size - 1, 1)
                break
            }

            if (idx > 0) {
                // Levágjuk a szemetet a fejléc elől
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

                // SUB-ID 0x01: Numerikus Értékek
                if (subId == 0x01 && pkt.size >= 9) {
                    val s = pkt[5].toInt() and 0xFF
                    val pr = pkt[6].toInt() and 0xFF
                    val rawPi = if (pkt.size > 8) (pkt[8].toInt() and 0xFF) else 0

                    val finger = !(s == 0xFF || s == 0 || pr == 0xFF || pr == 0)
                    isFingerIn = finger

                    if (finger) {
                        currentSpo2 = s
                        currentPulse = pr
                        currentPi = if (rawPi != 0xFF) (rawPi / 10.0f) else 0.0f
                        bleStatusMsg = "MÉRÉS FOLYAMATBAN"
                        updateOvernightStats(currentSpo2, currentPulse, currentPi, true)
                    } else {
                        currentSpo2 = null
                        currentPulse = null
                        currentPi = null
                        bleStatusMsg = "CSIPESZ LEVÉVE AZ UJJRÓL"
                        updateOvernightStats(null, null, null, false)
                    }
                }
                // SUB-ID 0x02: Pleth Hullámformák
                else if (subId == 0x02) {
                    val samples = pkt.copyOfRange(5, pkt.size - 1)
                    for (b in samples) {
                        val intVal = b.toInt() and 0xFF
                        val isBeat = (intVal and 0x80) != 0
                        val amp = intVal and 0x7F
                        lastWaveAmp = amp
                        if (isBeat) isBeatEvent = true
                    }
                }
            }
        }
    }

    private fun updateOvernightStats(spo2: Int?, pulse: Int?, pi: Float?, finger: Boolean) {
        val now = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        var isNumb = false
        var isApnea = false

        if (finger && pi != null) {
            if (pi < 0.8f) {
                isNumb = true
                numbTotalSeconds++
                if (!inNumbEpisode) {
                    inNumbEpisode = true
                    numbEpisodes++
                }
            } else if (pi >= 1.0f) {
                inNumbEpisode = false
            }

            if (pi < lowestPi) {
                lowestPi = pi
                lowestPiTime = now
            }
        }

        if (finger && spo2 != null) {
            spo2Baseline = (spo2Baseline * 0.995f) + (spo2 * 0.005f)
            val drop = spo2Baseline - spo2
            if (drop >= 3.0f || spo2 < 91) {
                isApnea = true
                if (!inApneaEpisode) {
                    inApneaEpisode = true
                    apneaEpisodes++
                }
            } else if (drop < 1.5f && spo2 >= 92) {
                inApneaEpisode = false
            }

            if (spo2 < lowestSpo2) {
                lowestSpo2 = spo2
                lowestSpo2Time = now
            }
        }

        // CSV Logolás másodpercenként ha aktív
        if (csvLogger.isRecording) {
            val statusList = mutableListOf<String>()
            if (isNumb) statusList.add("ZSIBBADÁS (PI < 0.8%)")
            if (isApnea) statusList.add("ALVÁSI APNOÉ")
            if (!finger) statusList.add("CSIPESZ LEVÉVE")
            val statusTxt = if (statusList.isEmpty()) "NORMÁL" else statusList.joinToString(" | ")

            csvLogger.logSecond(spo2, pulse, pi, statusTxt, isApnea, isNumb)
        }

        // Éjszakai idősáv pont tárolása
        if (finger && pi != null) {
            val pt = JSONObject().apply {
                put("time", now)
                put("spo2", spo2 ?: 95)
                put("pulse", pulse ?: 70)
                put("pi", pi)
                put("is_numb", isNumb)
                put("is_apnea", isApnea)
            }
            overnightHistory.add(pt)
            if (overnightHistory.size > 28800) overnightHistory.removeAt(0) // max 8 hours
        }
    }

    private fun startPeriodicTick() {
        liveTickJob = serviceScope.launch {
            while (isActive) {
                delay(100) // 10 Hz UI broadcast
                val payload = buildLivePayload()
                withContext(Dispatchers.Main) {
                    onTelemetryListener?.invoke(payload)
                }
                isBeatEvent = false
            }
        }
    }

    fun buildLivePayload(): JSONObject {
        val recSecs = csvLogger.recordSeconds
        val recHrs = max(recSecs.toDouble(), 60.0) / 3600.0
        val odi = if (csvLogger.isRecording) ((apneaEpisodes / recHrs) * 10.0).roundToInt() / 10.0 else 0.0
        val apneaSev = when {
            odi < 5.0 -> "NORMÁL (<5/h)"
            odi < 15.0 -> "ENYHE APNOÉ"
            odi < 30.0 -> "KÖZEPES APNOÉ"
            else -> "SÚLYOS APNOÉ"
        }

        return JSONObject().apply {
            put("type", "live")
            put("ble_connected", isBleConnected)
            put("ble_scanning", isBleScanning)
            put("device_name", connectedDeviceName)
            put("ble_status_msg", bleStatusMsg)
            put("recording", csvLogger.isRecording)
            put("record_seconds", recSecs)
            put("finger_in", isFingerIn)
            put("spo2", currentSpo2 ?: JSONObject.NULL)
            put("pulse", currentPulse ?: JSONObject.NULL)
            put("pi", currentPi ?: JSONObject.NULL)
            put("wave", lastWaveAmp)
            put("beat", isBeatEvent)

            put("stats", JSONObject().apply {
                put("episodes", numbEpisodes)
                put("total_numb_minutes", (numbTotalSeconds / 60.0 * 10.0).roundToInt() / 10.0)
                put("lowest_pi", if (lowestPi >= 99.0f) JSONObject.NULL else lowestPi)
                put("lowest_pi_time", lowestPiTime)
                put("apnea_episodes", apneaEpisodes)
                put("odi", odi)
                put("apnea_severity", apneaSev)
                put("lowest_spo2", if (lowestSpo2 >= 99) JSONObject.NULL else lowestSpo2)
                put("lowest_spo2_time", lowestSpo2Time)
                put("extra_beats", 0)
            })
        }
    }

    fun getHistoryPayload(): JSONObject {
        val arr = JSONArray()
        synchronized(overnightHistory) {
            for (p in overnightHistory) arr.put(p)
        }
        return JSONObject().apply {
            put("type", "history")
            put("points", arr)
        }
    }
}
