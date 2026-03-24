// BluetoothSyncService.kt
package com.example.sensor_recorder.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.example.sensor_recorder.MainActivity
import com.example.sensor_recorder.R
import com.example.sensor_recorder.SensorRecorderService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.UUID

class BluetoothSyncService : Service() {

    // ── Binder ──────────────────────────────────────────────────────────────
    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): BluetoothSyncService = this@BluetoothSyncService
    }

    override fun onBind(intent: Intent): IBinder = binder

    // ── State exposed to UI (polled by MainActivity) ─────────────────────────
    @Volatile var connectionState: BtConnectionState = BtConnectionState.IDLE
    @Volatile var peerDeviceName: String? = null
    @Volatile var lastSyncReport: SyncReport? = null
    @Volatile var errorMessage: String? = null
    @Volatile var pendingSyncResult: ClockOffsetEstimator.Result? = null

    // ── Internal ─────────────────────────────────────────────────────────────
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var socket: BluetoothSocket? = null
    private var serverSocket: BluetoothServerSocket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private val estimator = ClockOffsetEstimator()
    private val pongChannel = Channel<SyncMessage.Pong>(Channel.BUFFERED)
    private var currentSessionId: String? = null
    private var isConnected = false
    private var role: DeviceRole = DeviceRole.STANDALONE

    // ── Binding to SensorRecorderService ─────────────────────────────────────
    private var sensorService: SensorRecorderService? = null
    private val sensorServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            sensorService = (binder as SensorRecorderService.LocalBinder).getService()
            Timber.d("BluetoothSyncService bound to SensorRecorderService")
        }
        override fun onServiceDisconnected(name: ComponentName) {
            sensorService = null
            Timber.w("BluetoothSyncService lost SensorRecorderService binding")
        }
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        Timber.i("BluetoothSyncService created")
        createNotificationChannel()
        bindService(
            Intent(this, SensorRecorderService::class.java),
            sensorServiceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.i("BluetoothSyncService destroyed")
        handleDisconnect()
        try { unbindService(sensorServiceConnection) } catch (_: Exception) {}
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_CONNECT -> {
                role = DeviceRole.CONTROLLER
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS) ?: return START_NOT_STICKY
                val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val device = btManager.adapter.getRemoteDevice(address)
                connectToDevice(device)
            }
            ACTION_LISTEN -> {
                role = DeviceRole.WORKER
                startListening()
            }
            ACTION_DISCONNECT -> handleDisconnect()
            ACTION_SYNC_CLOCKS -> {
                if (role == DeviceRole.CONTROLLER && isConnected) {
                    serviceScope.launch { runClockSync() }
                }
            }
        }
        return START_NOT_STICKY
    }

    // ── Controller: connect to device ────────────────────────────────────────
    private fun connectToDevice(device: BluetoothDevice) {
        serviceScope.launch {
            var attempt = 0
            while (attempt < 3 && !isConnected) {
                attempt++
                try {
                    Timber.i("Connecting to ${device.name} (${device.address}) - Attempt $attempt")
                    connectionState = BtConnectionState.CONNECTING
                    startForegroundIfNeeded()

                    val btSocket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                    socket = btSocket

                    // Cancel discovery to avoid slowing connection
                    val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                    btManager.adapter.cancelDiscovery()

                    withContext(Dispatchers.IO) {
                        btSocket.connect()
                    }
                    
                    isConnected = true
                    peerDeviceName = device.name
                    Timber.i("Connected to ${device.name}")

                    setupStreams(btSocket)
                    startReaderLoop() // Start reader loop IMMEDIATELY so we can receive pongs
                    connectionState = BtConnectionState.SYNCING
                    runClockSync()
                    connectionState = BtConnectionState.READY
                    break // Success, exit retry loop
                } catch (e: IOException) {
                    Timber.e(e, "Connection failed on attempt $attempt")
                    try { socket?.close() } catch (_: Exception) {}
                    if (attempt == 3) {
                        errorMessage = "Connection failed: ${e.message}"
                        connectionState = BtConnectionState.ERROR
                        handleDisconnect()
                    } else {
                        delay(500) // Wait before retrying
                    }
                }
            }
        }
    }

    // ── Controller: clock sync ────────────────────────────────────────────────
    suspend fun runClockSync() {
        Timber.i("Starting clock sync (10 rounds)")
        connectionState = BtConnectionState.SYNCING
        estimator.clear()

        // Clear any old pongs
        while (pongChannel.tryReceive().isSuccess) {}

        repeat(10) { i ->
            try {
                val tSend = SystemClock.elapsedRealtimeNanos()
                sendLine(SyncMessage.Ping(tSend).toJson())

                val pong = withTimeoutOrNull(2000) { pongChannel.receive() }
                if (pong == null) {
                    Timber.w("Clock sync round $i timed out waiting for PONG")
                    return
                }

                val tRecv = SystemClock.elapsedRealtimeNanos()

                estimator.addSample(tSend, pong.tRecvNs, pong.tReplyNs, tRecv)
                Timber.d("Ping $i RTT=${(tRecv - tSend) / 1_000_000}ms")
                delay(100)
            } catch (e: Exception) {
                Timber.e(e, "Clock sync error on round $i")
                handleDisconnect()
                return
            }
        }

        val result = estimator.estimate()
        if (result == null) {
            Timber.w("Clock sync produced no result (not enough samples)")
            errorMessage = "Clock sync failed — not enough samples"
            connectionState = BtConnectionState.ERROR
            return
        }

        pendingSyncResult = result
        Timber.i("Clock sync done: offset=${result.offsetNs / 1_000_000}ms ±${result.stdDevNs / 1_000_000}ms (${result.sampleCount} samples)")

        // Provide a preliminary sync report for the UI
        lastSyncReport = SyncReport(
            offsetNs = result.offsetNs,
            stdDevNs = result.stdDevNs,
            sampleCount = result.sampleCount,
            workerAccelCount = 0,
            workerGyroCount = 0
        )

        // Push offset into SensorRecorderService so it's written to Metadata.json
        sensorService?.btClockOffsetNs = result.offsetNs
        sensorService?.btClockOffsetStdDevNs = result.stdDevNs
        sensorService?.btClockOffsetSamples = result.sampleCount
        sensorService?.btPeerDevice = peerDeviceName

        connectionState = BtConnectionState.READY
    }

    // ── Controller: send command ──────────────────────────────────────────────
    fun sendCommand(commandType: String) {
        val sessionId = currentSessionId ?: run {
            // Generate session ID on first START
            val id = java.util.UUID.randomUUID().toString().take(8)
            currentSessionId = id
            id
        }
        Timber.d("Sending command $commandType (session=$sessionId)")
        sendLine(SyncMessage.Command(commandType, sessionId).toJson())

        if (commandType == "CMD_START") connectionState = BtConnectionState.RECORDING
        if (commandType == "CMD_STOP") {
            // state will update to READY once Worker STATUS arrives
        }
    }

    // ── Worker: listen for incoming connection ────────────────────────────────
    private fun startListening() {
        serviceScope.launch {
            try {
                Timber.i("Worker: opening RFCOMM server socket, listening for Controller")
                connectionState = BtConnectionState.CONNECTING
                startForegroundIfNeeded()

                val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                val ss = btManager.adapter.listenUsingRfcommWithServiceRecord("IMUSyncService", SERVICE_UUID)
                serverSocket = ss

                val btSocket = withContext(Dispatchers.IO) { ss.accept() }
                ss.close()
                serverSocket = null

                socket = btSocket
                isConnected = true
                peerDeviceName = btSocket.remoteDevice.name
                Timber.i("Worker: Controller connected from ${btSocket.remoteDevice.name}")

                setupStreams(btSocket)
                connectionState = BtConnectionState.SYNCING
                startReaderLoop()
            } catch (e: IOException) {
                Timber.e(e, "Worker: server socket error")
                errorMessage = "Listen failed: ${e.message}"
                connectionState = BtConnectionState.ERROR
                handleDisconnect()
            }
        }
    }

    // ── Shared reader loop ────────────────────────────────────────────────────
    private var pingCount = 0

    private fun startReaderLoop() {
        serviceScope.launch(Dispatchers.IO) {
            Timber.d("Reader loop started (role=$role)")
            try {
                while (isConnected) {
                    val line = reader?.readLine() ?: break
                    val msg = SyncMessage.fromJson(line)
                    if (msg == null) {
                        Timber.w("Unparseable message: $line")
                        continue
                    }
                    handleMessage(msg)
                }
            } catch (e: IOException) {
                if (isConnected) Timber.e(e, "Reader loop IO error")
            }
            Timber.i("Reader loop ended")
            handleDisconnect()
        }
    }

    private fun handleMessage(msg: SyncMessage) {
        when (msg) {
            // ── Worker receives these ────────────────────────────────────────
            is SyncMessage.Ping -> {
                val tRecv = SystemClock.elapsedRealtimeNanos()
                val tReply = SystemClock.elapsedRealtimeNanos()
                sendLine(SyncMessage.Pong(msg.tNs, tRecv, tReply).toJson())
                pingCount++
                if (pingCount >= 10) {
                    Timber.i("Worker: received 10 pings, clock sync complete")
                    connectionState = BtConnectionState.READY
                }
            }
            is SyncMessage.Command -> {
                val tNs = SystemClock.elapsedRealtimeNanos()
                Timber.d("Worker received command: ${msg.commandType}")
                when (msg.commandType) {
                    "CMD_START" -> {
                        currentSessionId = msg.sessionId
                        sensorService?.startRecording()
                        connectionState = BtConnectionState.RECORDING
                        Timber.i("Worker: recording started")
                    }
                    "CMD_STOP" -> {
                        sensorService?.stopRecording()
                        connectionState = BtConnectionState.READY
                        Timber.i("Worker: recording stopped, sending STATUS")
                        serviceScope.launch {
                            delay(500) // let stopRecording flush and finalize counts
                            val accel = sensorService?.sampleCountAccel ?: 0
                            val gyro = sensorService?.sampleCountGyro ?: 0
                            sendLine(SyncMessage.Status(false, accel, gyro).toJson())
                        }
                    }
                    "CMD_SYNC_TAP" -> {
                        sensorService?.logSyncTap()
                        Timber.d("Worker: sync tap executed")
                    }
                }
                sendLine(SyncMessage.Ack(msg.commandType, msg.sessionId, tNs).toJson())
            }

            // ── Controller receives these ─────────────────────────────────────
            is SyncMessage.Pong -> {
                if (role == DeviceRole.CONTROLLER) {
                    pongChannel.trySend(msg)
                }
            }
            is SyncMessage.Ack -> {
                Timber.d("Controller: Worker ACK for ${msg.cmd}")
            }
            is SyncMessage.Status -> {
                Timber.i("Controller: Worker STATUS — accel=${msg.accelCount}, gyro=${msg.gyroCount}")
                val syncResult = pendingSyncResult
                lastSyncReport = if (syncResult != null) {
                    SyncReport(
                        offsetNs = syncResult.offsetNs,
                        stdDevNs = syncResult.stdDevNs,
                        sampleCount = syncResult.sampleCount,
                        workerAccelCount = msg.accelCount,
                        workerGyroCount = msg.gyroCount
                    )
                } else {
                    SyncReport(
                        offsetNs = 0,
                        stdDevNs = 0,
                        sampleCount = 0,
                        workerAccelCount = msg.accelCount,
                        workerGyroCount = msg.gyroCount
                    )
                }
                connectionState = BtConnectionState.READY
                currentSessionId = null
            }

            else -> Timber.w("Unhandled message type: $msg")
        }
    }

    // ── Socket helpers ────────────────────────────────────────────────────────
    private fun setupStreams(btSocket: BluetoothSocket) {
        reader = BufferedReader(InputStreamReader(btSocket.inputStream))
        writer = BufferedWriter(OutputStreamWriter(btSocket.outputStream))
    }

    private fun sendLine(json: String) {
        try {
            writer?.write(json + "\n")
            writer?.flush()
        } catch (e: IOException) {
            Timber.e(e, "Send failed")
            handleDisconnect()
        }
    }

    private fun handleDisconnect() {
        if (!isConnected && connectionState == BtConnectionState.IDLE) return
        Timber.i("Disconnecting (was connected to $peerDeviceName)")
        isConnected = false
        connectionState = BtConnectionState.IDLE
        peerDeviceName = null
        pingCount = 0
        try { reader?.close() } catch (_: Exception) {}
        try { writer?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        try { serverSocket?.close() } catch (_: Exception) {}
        reader = null
        writer = null
        socket = null
        serverSocket = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    // ── Foreground service (required for connectedDevice type on API 34+) ─────
    private fun startForegroundIfNeeded() {
        startForeground(NOTIFICATION_ID, createNotification())
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

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("IMU Sync")
            .setContentText("Bluetooth sync active")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        const val ACTION_CONNECT = "com.example.sensor_recorder.CONNECT"
        const val ACTION_LISTEN = "com.example.sensor_recorder.LISTEN"
        const val ACTION_DISCONNECT = "com.example.sensor_recorder.DISCONNECT"
        const val ACTION_SYNC_CLOCKS = "com.example.sensor_recorder.SYNC_CLOCKS"

        const val EXTRA_DEVICE_ADDRESS = "DEVICE_ADDRESS"

        private const val CHANNEL_ID = "BtSyncChannel"
        private const val NOTIFICATION_ID = 2
    }
}
