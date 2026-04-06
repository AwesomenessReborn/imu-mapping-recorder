package com.example.sensor_recorder.sync

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.example.sensor_recorder.MainActivity
import com.example.sensor_recorder.R
import timber.log.Timber
import java.util.UUID

/**
 * Foreground service that manages the BLE connection to SensorTile.box PRO
 * running DATALOG2 v3.2.0 firmware. Sends PnPL start_log / stop_log commands
 * over BLE GATT. The SensorTile records to its SD card independently.
 *
 * Coordinated by MainActivity — recording start/stop mirrors the phone recording.
 * If SensorTile is not connected when recording starts, recording proceeds
 * phone-only (graceful degradation).
 */
@SuppressLint("MissingPermission")
class SensorTileService : Service() {

    companion object {
        const val ACTION_SCAN       = "com.example.sensor_recorder.ACTION_ST_SCAN"
        const val ACTION_DISCONNECT = "com.example.sensor_recorder.ACTION_ST_DISCONNECT"

        private const val CHANNEL_ID      = "BtSyncChannel" // reuse existing channel
        private const val NOTIFICATION_ID = 3

        // DATALOG2 v3.x PnPL BLE interface (BlueST protocol)
        // These UUIDs come from the BlueST v1 SDK / DATALOG2 firmware source.
        //
        // VERIFY: if connectionState goes to ERROR after service discovery, enable Timber
        // logging and grep for "SERVICE:" and "CHAR:" lines — they list every UUID the
        // device advertises. Or use nRF Connect to scan "SensorTile.box Pro" manually.
        private val PNPL_SERVICE_UUID    = UUID.fromString("00000000-000e-11e1-9ab4-0002a5d5c51b")
        private val PNPL_WRITE_CHAR_UUID = UUID.fromString("00000001-000e-11e1-ac36-0002a5d5c51b")

        // Known BLE advertisement names for SensorTile.box PRO running DATALOG2.
        // The STBLESensors app shows it as "HSD2v32"; the firmware may also advertise
        // "SensorTile.box Pro" depending on version. We match any known name.
        // If your device isn't found, check the "BLE SCAN:" lines in logcat to see
        // what name it's actually advertising and add it here.
        private val KNOWN_DEVICE_NAMES = setOf(
            "SensorTile.box Pro",
            "HSD2v32",
            "STEVAL-MKBOXPRO",
            "SBP"
        )

        private const val SCAN_TIMEOUT_MS = 15_000L
    }

    // ── Binder ────────────────────────────────────────────────────────────────
    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): SensorTileService = this@SensorTileService
    }
    override fun onBind(intent: Intent): IBinder = binder

    // ── State exposed to MainActivity (polled every 200ms) ────────────────────
    @Volatile var connectionState: SensorTileConnectionState = SensorTileConnectionState.IDLE
    @Volatile var errorMessage: String? = null
    @Volatile var connectedDeviceName: String? = null

    // ── Internal ──────────────────────────────────────────────────────────────
    private var gatt: BluetoothGatt? = null
    private var pnplChar: BluetoothGattCharacteristic? = null
    private var isScanning = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val leScanner by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter.bluetoothLeScanner
    }

    // ── BLE scan ──────────────────────────────────────────────────────────────

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // With neverForLocation, read name from the advertisement scan record directly.
            // result.device.name reads from the OS BT cache which may be null for unpaired devices.
            val name = result.scanRecord?.deviceName
                ?: try { result.device.name } catch (_: Exception) { null }
            // Log every named device seen so we can identify the real advertisement name
            if (name != null) {
                Timber.d("BLE SCAN: \"$name\"  addr=${result.device.address}  rssi=${result.rssi}")
            }
            if (name != null && name in KNOWN_DEVICE_NAMES) {
                Timber.i("SensorTile matched \"$name\" at ${result.device.address}")
                stopScan()
                connectionState = SensorTileConnectionState.CONNECTING
                connectedDeviceName = name
                updateNotification()
                gatt = result.device.connectGatt(
                    this@SensorTileService, false, gattCallback, BluetoothDevice.TRANSPORT_LE
                )
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Timber.e("BLE scan failed: errorCode=$errorCode")
            isScanning = false
            connectionState = SensorTileConnectionState.ERROR
            errorMessage = "BLE scan failed (code $errorCode)"
            updateNotification()
        }
    }

    // ── GATT callbacks ────────────────────────────────────────────────────────

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Timber.i("SensorTile GATT connected — discovering services")
                    connectionState = SensorTileConnectionState.CONFIGURING
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Timber.i("SensorTile GATT disconnected (status=$status)")
                    pnplChar = null
                    connectedDeviceName = null
                    if (connectionState != SensorTileConnectionState.IDLE) {
                        connectionState = SensorTileConnectionState.ERROR
                        errorMessage = "Disconnected (GATT status $status)"
                    }
                    gatt.close()
                    this@SensorTileService.gatt = null
                    updateNotification()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Timber.e("Service discovery failed: status=$status")
                connectionState = SensorTileConnectionState.ERROR
                errorMessage = "Service discovery failed (status $status)"
                updateNotification()
                return
            }

            // Log every service + characteristic for easy UUID verification
            Timber.d("=== SensorTile GATT services ===")
            gatt.services.forEach { svc ->
                Timber.d("SERVICE: ${svc.uuid}")
                svc.characteristics.forEach { chr ->
                    Timber.d("  CHAR: ${chr.uuid}  props=0x${chr.properties.toString(16)}")
                }
            }
            Timber.d("================================")

            val service = gatt.getService(PNPL_SERVICE_UUID)
            if (service == null) {
                Timber.e("PnPL service $PNPL_SERVICE_UUID not found — see SERVICE: lines above")
                connectionState = SensorTileConnectionState.ERROR
                errorMessage = "PnPL service not found — check logcat for correct UUID"
                updateNotification()
                return
            }

            val char = service.getCharacteristic(PNPL_WRITE_CHAR_UUID)
            if (char == null) {
                Timber.e("PnPL write char $PNPL_WRITE_CHAR_UUID not found — see CHAR: lines above")
                connectionState = SensorTileConnectionState.ERROR
                errorMessage = "PnPL characteristic not found — check logcat for correct UUID"
                updateNotification()
                return
            }

            pnplChar = char
            // connectedDeviceName already set in onScanResult when device was found
            // Request higher MTU to accommodate longer PnPL JSON (e.g. sensor config)
            gatt.requestMtu(247)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Timber.i("MTU changed to $mtu (status=$status)")
            connectionState = SensorTileConnectionState.READY
            updateNotification()
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Timber.d("PnPL write OK")
            } else {
                Timber.e("PnPL write failed: status=$status")
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SCAN -> startScan()
            ACTION_DISCONNECT -> disconnect()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnect()
    }

    // ── Public API (called directly via binder) ───────────────────────────────

    /** Send PnPL start_log command. No-op if not connected. */
    fun startLog() {
        if (connectionState != SensorTileConnectionState.READY) {
            Timber.w("startLog() called but state=$connectionState — ignoring")
            return
        }
        sendPnpl("""{"log_controller":{"start_log":{}}}""")
        connectionState = SensorTileConnectionState.RECORDING
        updateNotification()
    }

    /** Send PnPL stop_log command. No-op if not recording. */
    fun stopLog() {
        if (pnplChar == null) {
            Timber.w("stopLog() called but not connected — ignoring")
            return
        }
        sendPnpl("""{"log_controller":{"stop_log":{}}}""")
        connectionState = SensorTileConnectionState.READY
        updateNotification()
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun sendPnpl(json: String) {
        val char = pnplChar ?: run {
            Timber.e("Cannot send PnPL: no characteristic (state=$connectionState)")
            return
        }
        val bytes = json.toByteArray(Charsets.UTF_8)
        Timber.d("PnPL → $json")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt?.writeCharacteristic(char, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            char.value = bytes
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            gatt?.writeCharacteristic(char)
        }
    }

    private fun startScan() {
        if (isScanning) {
            Timber.d("Scan already in progress")
            return
        }
        Timber.i("Starting BLE scan for SensorTile.box PRO (names: $KNOWN_DEVICE_NAMES)")
        connectionState = SensorTileConnectionState.SCANNING
        errorMessage = null
        isScanning = true
        updateNotification()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        // No ScanFilter — OS-level name filtering silently drops devices whose advertisement
        // name doesn't match exactly. We filter by name in onScanResult instead and log
        // all named devices so the real advertisement name is visible in logcat.
        leScanner.startScan(null, settings, scanCallback)

        // Auto-stop scan if device not found within timeout
        mainHandler.postDelayed({
            if (isScanning) {
                stopScan()
                if (connectionState == SensorTileConnectionState.SCANNING) {
                    Timber.w("Scan timeout — SensorTile not found (check logcat BLE SCAN: lines for nearby device names)")
                    connectionState = SensorTileConnectionState.ERROR
                    errorMessage = "Not found — check logcat for nearby BLE device names"
                    updateNotification()
                }
            }
        }, SCAN_TIMEOUT_MS)
    }

    private fun stopScan() {
        if (!isScanning) return
        isScanning = false
        leScanner.stopScan(scanCallback)
    }

    private fun disconnect() {
        mainHandler.removeCallbacksAndMessages(null)
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        pnplChar = null
        connectedDeviceName = null
        connectionState = SensorTileConnectionState.IDLE
        errorMessage = null
        updateNotification()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bluetooth Sync Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val stateText = when (connectionState) {
            SensorTileConnectionState.IDLE       -> "Not connected"
            SensorTileConnectionState.SCANNING   -> "Scanning..."
            SensorTileConnectionState.CONNECTING -> "Connecting..."
            SensorTileConnectionState.CONFIGURING -> "Configuring..."
            SensorTileConnectionState.READY      -> "Ready"
            SensorTileConnectionState.RECORDING  -> "Recording"
            SensorTileConnectionState.ERROR      -> "Error"
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SensorTile.box PRO")
            .setContentText(stateText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }
}
