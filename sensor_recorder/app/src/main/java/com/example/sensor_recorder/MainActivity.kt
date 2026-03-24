// MainActivity.kt
package com.example.sensor_recorder

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.sensor_recorder.sync.BluetoothSyncService
import com.example.sensor_recorder.sync.BtConnectionState
import com.example.sensor_recorder.sync.DeviceRole
import com.example.sensor_recorder.sync.SyncReport
import com.example.sensor_recorder.ui.theme.Sensor_recorderTheme
import kotlinx.coroutines.delay
import timber.log.Timber
import java.io.File
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var sensorService: SensorRecorderService? = null
    private var btSyncService: BluetoothSyncService? = null
    private var isSensorServiceBound = mutableStateOf(false)
    private var isBtSyncServiceBound = mutableStateOf(false)

    private val sensorServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            sensorService = (binder as SensorRecorderService.LocalBinder).getService()
            isSensorServiceBound.value = true
            Timber.i("SensorRecorderService connected")
        }
        override fun onServiceDisconnected(arg0: ComponentName) {
            isSensorServiceBound.value = false
            sensorService = null
            Timber.w("SensorRecorderService disconnected")
        }
    }

    private val btSyncServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, binder: IBinder) {
            btSyncService = (binder as BluetoothSyncService.LocalBinder).getService()
            isBtSyncServiceBound.value = true
            Timber.i("BluetoothSyncService connected")
        }
        override fun onServiceDisconnected(arg0: ComponentName) {
            isBtSyncServiceBound.value = false
            btSyncService = null
            Timber.w("BluetoothSyncService disconnected")
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Timber.d("Permission results: $permissions")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Intent(this, SensorRecorderService::class.java).also { intent ->
            bindService(intent, sensorServiceConnection, Context.BIND_AUTO_CREATE)
        }
        Intent(this, BluetoothSyncService::class.java).also { intent ->
            bindService(intent, btSyncServiceConnection, Context.BIND_AUTO_CREATE)
        }

        requestBluetoothPermissions()
        ExportUtils.cleanupOldZips(this)
        enableEdgeToEdge()

        setContent {
            Sensor_recorderTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    RecorderScreen(
                        modifier = Modifier.padding(innerPadding),
                        sensorService = sensorService,
                        btSyncService = btSyncService,
                        isSensorServiceBound = isSensorServiceBound.value
                    )
                }
            }
        }
    }

    private fun requestBluetoothPermissions() {
        Timber.d("Requesting BT permissions, SDK=${Build.VERSION.SDK_INT}")
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter == null) {
            Timber.e("BluetoothAdapter is null — device has no BT")
            Toast.makeText(this, "Bluetooth is not available on this device", Toast.LENGTH_LONG).show()
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            Timber.w("Bluetooth is disabled — user must enable manually")
        }

        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            perms.add(Manifest.permission.BLUETOOTH)
            perms.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        if (perms.isNotEmpty()) requestPermissionLauncher.launch(perms.toTypedArray())
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isSensorServiceBound.value) unbindService(sensorServiceConnection)
        if (isBtSyncServiceBound.value) unbindService(btSyncServiceConnection)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecorderScreen(
    modifier: Modifier = Modifier,
    sensorService: SensorRecorderService?,
    btSyncService: BluetoothSyncService?,
    isSensorServiceBound: Boolean
) {
    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("DeviceRolePrefs", Context.MODE_PRIVATE) }

    // ── Sensor service state ─────────────────────────────────────────────────
    var isRecording by remember { mutableStateOf(false) }
    var durationSeconds by remember { mutableStateOf(0L) }
    var accelCount by remember { mutableStateOf(0L) }
    var gyroCount by remember { mutableStateOf(0L) }
    var accelRate by remember { mutableStateOf(0.0) }
    var gyroRate by remember { mutableStateOf(0.0) }
    var lastRecordingDir by remember { mutableStateOf<File?>(null) }
    var isExporting by remember { mutableStateOf(false) }

    // ── BT sync state ────────────────────────────────────────────────────────
    var deviceRole by remember {
        mutableStateOf(DeviceRole.valueOf(
            sharedPrefs.getString("role", DeviceRole.STANDALONE.name) ?: DeviceRole.STANDALONE.name
        ))
    }
    var btState by remember { mutableStateOf(BtConnectionState.IDLE) }
    var peerDeviceName by remember { mutableStateOf<String?>(null) }
    var syncReport by remember { mutableStateOf<SyncReport?>(null) }
    var showDevicePicker by remember { mutableStateOf(false) }

    // ── Countdown state ──────────────────────────────────────────────────────
    var startDelaySeconds by remember { mutableIntStateOf(0) }
    var isCountingDown by remember { mutableStateOf(false) }
    var countdownValue by remember { mutableIntStateOf(0) }

    // ── Poll sensor service ──────────────────────────────────────────────────
    LaunchedEffect(isSensorServiceBound) {
        while (true) {
            if (isSensorServiceBound && sensorService != null) {
                isRecording = sensorService.isRecording.get()
                durationSeconds = if (isRecording)
                    (SystemClock.elapsedRealtimeNanos() - sensorService.startTimeNs) / 1_000_000_000L
                else 0L
                accelCount = sensorService.sampleCountAccel
                gyroCount = sensorService.sampleCountGyro
                accelRate = sensorService.getAccelRateHz()
                gyroRate = sensorService.getGyroRateHz()
                lastRecordingDir = sensorService.lastRecordingDir
            }
            delay(100)
        }
    }

    // ── Poll BT sync service ─────────────────────────────────────────────────
    LaunchedEffect(btSyncService) {
        while (true) {
            btSyncService?.let { svc ->
                btState = svc.connectionState
                peerDeviceName = svc.peerDeviceName
                syncReport = svc.lastSyncReport
            }
            delay(200)
        }
    }

    // ── Device picker dialog ─────────────────────────────────────────────────
    if (showDevicePicker) {
        val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val bondedDevices: List<BluetoothDevice> = try {
            btManager.adapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            Timber.e(e, "No BT permission to list bonded devices")
            emptyList()
        }
        AlertDialog(
            onDismissRequest = { showDevicePicker = false },
            title = { Text("Select Worker Device") },
            text = {
                if (bondedDevices.isEmpty()) {
                    Text("No paired devices found. Pair your phones in Android Settings first.")
                } else {
                    LazyColumn {
                        items(bondedDevices) { device ->
                            val name = try { device.name ?: device.address } catch (_: SecurityException) { device.address }
                            ListItem(
                                headlineContent = { Text(name) },
                                supportingContent = { Text(device.address) },
                                modifier = Modifier.clickable {
                                    showDevicePicker = false
                                    Timber.i("User selected device: $name (${device.address})")
                                    Intent(context, BluetoothSyncService::class.java).also {
                                        it.action = BluetoothSyncService.ACTION_CONNECT
                                        it.putExtra(BluetoothSyncService.EXTRA_DEVICE_ADDRESS, device.address)
                                        context.startForegroundService(it)
                                    }
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDevicePicker = false }) { Text("Cancel") }
            }
        )
    }

    // ── Countdown Effect ─────────────────────────────────────────────────────
    LaunchedEffect(isCountingDown) {
        if (isCountingDown) {
            while (countdownValue > 0) {
                delay(1000)
                countdownValue--
            }
            isCountingDown = false
            if (!isRecording) {
                if (deviceRole == DeviceRole.CONTROLLER) {
                    btSyncService?.sendCommand("CMD_START")
                }
                sensorService?.startRecording()
            }
        }
    }

    // ── UI ───────────────────────────────────────────────────────────────────
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("IMU Recorder", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

        // Role selector (hidden while recording)
        if (!isRecording) {
            DeviceRoleSelector(
                selectedRole = deviceRole,
                onRoleSelected = { newRole ->
                    Timber.i("Device role changed to $newRole")
                    deviceRole = newRole
                    sharedPrefs.edit().putString("role", newRole.name).apply()
                    if (newRole == DeviceRole.WORKER) {
                        // Auto-start listening when switched to Worker
                        Intent(context, BluetoothSyncService::class.java).also {
                            it.action = BluetoothSyncService.ACTION_LISTEN
                            context.startForegroundService(it)
                        }
                    }
                }
            )
        }

        // Status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isRecording) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (isRecording) "● RECORDING" else "READY",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (isRecording) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    String.format(Locale.US, "%02d:%02d", durationSeconds / 60, durationSeconds % 60),
                    style = MaterialTheme.typography.displayMedium
                )
            }
        }

        // BT status card (Controller or Worker only)
        if (deviceRole != DeviceRole.STANDALONE) {
            BluetoothStatusCard(
                role = deviceRole,
                state = btState,
                peerDeviceName = peerDeviceName,
                onSelectDevice = {
                    Timber.d("Select Device tapped")
                    showDevicePicker = true
                },
                onSyncClocks = {
                    Timber.d("Sync Clocks tapped")
                    Intent(context, BluetoothSyncService::class.java).also {
                        it.action = BluetoothSyncService.ACTION_SYNC_CLOCKS
                        context.startService(it)
                    }
                }
            )
        }

        // Stats
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatItem("Accel Samples", String.format(Locale.US, "%,d", accelCount))
            StatItem("Gyro Samples", String.format(Locale.US, "%,d", gyroCount))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            StatItem("Accel Rate", String.format(Locale.US, "%.1f Hz", accelRate))
            StatItem("Gyro Rate", String.format(Locale.US, "%.1f Hz", gyroRate))
        }

        HorizontalDivider()

        // Sync report card
        if (deviceRole == DeviceRole.CONTROLLER && syncReport != null) {
            SyncReportCard(report = syncReport!!)
        }

        Spacer(Modifier.weight(1f))

        // START DELAY SELECTOR
        if (!isRecording && !isCountingDown && deviceRole != DeviceRole.WORKER) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Start Delay:", style = MaterialTheme.typography.bodyLarge)
                SingleChoiceSegmentedButtonRow {
                    listOf(0, 3, 5).forEachIndexed { index, delayValue ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = 3),
                            onClick = { startDelaySeconds = delayValue },
                            selected = startDelaySeconds == delayValue
                        ) {
                            Text(if (delayValue == 0) "None" else "${delayValue}s")
                        }
                    }
                }
            }
        }

        // START / STOP
        val isStartEnabled = if (deviceRole == DeviceRole.CONTROLLER) {
            btState == BtConnectionState.READY && syncReport != null
        } else {
            true
        }

        if (isCountingDown) {
            Card(
                modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Starting in $countdownValue...",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            Button(
                onClick = {
                    Timber.d("Recording button tapped, isRecording=$isRecording")
                    if (isRecording) {
                        // STOP
                        if (deviceRole == DeviceRole.CONTROLLER) {
                            btSyncService?.sendCommand("CMD_STOP")
                        }
                        sensorService?.stopRecording()
                    } else {
                        // START
                        if (startDelaySeconds > 0) {
                            isCountingDown = true
                            countdownValue = startDelaySeconds
                        } else {
                            if (deviceRole == DeviceRole.CONTROLLER) {
                                btSyncService?.sendCommand("CMD_START")
                            }
                            sensorService?.startRecording()
                        }
                    }
                },
                enabled = isRecording || isStartEnabled,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(
                    if (isRecording) "STOP RECORDING" else "START RECORDING",
                    fontSize = 18.sp, fontWeight = FontWeight.Bold
                )
            }
        }

        // SYNC TAP
        Button(
            onClick = {
                Timber.d("Sync tap triggered")
                if (deviceRole == DeviceRole.CONTROLLER) {
                    btSyncService?.sendCommand("CMD_SYNC_TAP")
                }
                sensorService?.logSyncTap()
            },
            enabled = isRecording,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
        ) {
            Text("SYNC TAP ⚡", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }

        // SHARE RECORDING
        if (!isRecording && lastRecordingDir != null) {
            Button(
                onClick = {
                    isExporting = true
                    lastRecordingDir?.let { dir ->
                        if (!ExportUtils.shareRecording(context, dir)) {
                            Toast.makeText(context, "Failed to export recording", Toast.LENGTH_SHORT).show()
                        }
                    }
                    isExporting = false
                },
                enabled = !isExporting,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
            ) {
                Text(if (isExporting) "EXPORTING..." else "SHARE RECORDING", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ── Composables ──────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceRoleSelector(selectedRole: DeviceRole, onRoleSelected: (DeviceRole) -> Unit) {
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        DeviceRole.values().forEachIndexed { index, role ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = index, count = DeviceRole.values().size),
                onClick = { onRoleSelected(role) },
                selected = role == selectedRole
            ) {
                Text(role.name)
            }
        }
    }
}

@Composable
fun BluetoothStatusCard(
    role: DeviceRole,
    state: BtConnectionState,
    peerDeviceName: String?,
    onSelectDevice: () -> Unit,
    onSyncClocks: () -> Unit
) {
    val statusText = when (state) {
        BtConnectionState.IDLE -> if (role == DeviceRole.WORKER) "● Listening..." else "○ Not connected"
        BtConnectionState.CONNECTING -> "⟳ Connecting..."
        BtConnectionState.SYNCING -> "⟳ Syncing clocks..."
        BtConnectionState.READY -> "● Connected to ${peerDeviceName ?: "peer"} — Ready"
        BtConnectionState.RECORDING -> "● Recording with ${peerDeviceName ?: "peer"}"
        BtConnectionState.ERROR -> "✕ Error"
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Bluetooth Sync", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(statusText, style = MaterialTheme.typography.bodyLarge)
            if (role == DeviceRole.CONTROLLER) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onSelectDevice,
                        enabled = state == BtConnectionState.IDLE || state == BtConnectionState.ERROR
                    ) {
                        Text("Select Device")
                    }
                    OutlinedButton(
                        onClick = onSyncClocks,
                        enabled = state == BtConnectionState.READY
                    ) {
                        Text(if (state == BtConnectionState.SYNCING) "Syncing..." else "Sync Clocks")
                    }
                }
            }
        }
    }
}

@Composable
fun SyncReportCard(report: SyncReport) {
    val offsetMs = report.offsetNs / 1_000_000.0
    val stdDevMs = report.stdDevNs / 1_000_000.0

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Sync Report", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                String.format(Locale.US, "Clock offset:  %.2f ms ± %.2f ms", offsetMs, stdDevMs),
                style = MaterialTheme.typography.bodyMedium
            )
            Text("Samples used:  ${report.sampleCount}", style = MaterialTheme.typography.bodyMedium)
            if (report.workerAccelCount > 0 || report.workerGyroCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    String.format(Locale.US, "Worker accel:  %,d samples", report.workerAccelCount),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    String.format(Locale.US, "Worker gyro:   %,d samples", report.workerGyroCount),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}
