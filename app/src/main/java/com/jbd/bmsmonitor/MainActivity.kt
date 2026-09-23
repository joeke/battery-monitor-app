package com.jbd.bmsmonitor

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jbd.bmsmonitor.model.BmsDeviceState
import com.jbd.bmsmonitor.model.BmsSettings
import com.jbd.bmsmonitor.model.BmsTelemetry
import com.jbd.bmsmonitor.model.ConnectionStatus
import com.jbd.bmsmonitor.model.DiscoveredBms
import com.jbd.bmsmonitor.model.ServerUploadConfig
import com.jbd.bmsmonitor.model.SettingsNote
import com.jbd.bmsmonitor.model.settingsAdvice
import com.jbd.bmsmonitor.network.ServerConnectionCheckResult
import java.net.URI
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private val mainViewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { JbdTheme { JbdApp(mainViewModel) } }
    }

    override fun onStart() {
        super.onStart()
        mainViewModel.onAppForegrounded()
    }

    override fun onStop() {
        mainViewModel.onAppBackgrounded()
        super.onStop()
    }
}

@Composable
private fun JbdApp(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    var permissionRevision by remember { mutableIntStateOf(0) }
    val requiredPermissions = remember { bluetoothPermissions() }
    val permissionsGranted = remember(permissionRevision) {
        requiredPermissions.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        permissionRevision++
        if (it.values.all { granted -> granted }) viewModel.startScan()
    }
    val enableBluetoothLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { permissionRevision++ }
    val updateInstallPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (context.packageManager.canRequestPackageInstalls()) {
            viewModel.installDownloadedUpdate()
        }
    }

    val discovered by viewModel.discovered.collectAsStateWithLifecycle()
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val backgroundDisconnectSeconds by viewModel.backgroundDisconnectSeconds.collectAsStateWithLifecycle()
    val serverUploadConfig by viewModel.serverUploadConfig.collectAsStateWithLifecycle()
    val serverConnectionCheck by viewModel.serverConnectionCheck.collectAsStateWithLifecycle()
    val appUpdateState by viewModel.appUpdateState.collectAsStateWithLifecycle()
    var selectedAddress by remember { mutableStateOf<String?>(null) }
    var showAppSettings by remember { mutableStateOf(false) }
    val selected = selectedAddress?.let(devices::get)

    BackHandler(enabled = showAppSettings) { showAppSettings = false }
    BackHandler(enabled = selected != null && !showAppSettings) { selectedAddress = null }

    when {
        !viewModel.bluetoothAvailable -> BlockingMessage(
            title = "Bluetooth LE is not available",
            body = "Battery Monitor needs a phone or tablet with Bluetooth Low Energy support.",
        )
        !permissionsGranted -> BlockingMessage(
            title = "Nearby devices permission",
            body = "Allow Bluetooth access to find and connect to your BMS. Scan results are not used for location.",
            action = "Allow access",
            onAction = { permissionLauncher.launch(requiredPermissions) },
        )
        !viewModel.bluetoothEnabled -> BlockingMessage(
            title = "Turn on Bluetooth",
            body = "Bluetooth must be enabled before the app can find your BMS devices.",
            action = "Turn on",
            onAction = {
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            },
        )
        showAppSettings -> AppSettingsScreen(
            backgroundDisconnectSeconds = backgroundDisconnectSeconds,
            onBackgroundDisconnectSecondsChange = viewModel::setBackgroundDisconnectSeconds,
            serverUploadConfig = serverUploadConfig,
            serverConnectionCheck = serverConnectionCheck,
            onServerUploadEnabledChange = viewModel::setServerUploadEnabled,
            onSaveServerUploadConfiguration = viewModel::saveServerUploadConfiguration,
            onServerUploadDraftChange = viewModel::clearServerConnectionCheck,
            appUpdateState = appUpdateState,
            onCheckForUpdate = viewModel::checkForAppUpdate,
            onDownloadUpdate = viewModel::downloadAppUpdate,
            onInstallUpdate = {
                if (context.packageManager.canRequestPackageInstalls()) {
                    viewModel.installDownloadedUpdate()
                } else {
                    updateInstallPermissionLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                }
            },
            onBack = { showAppSettings = false },
        )
        selected != null -> DeviceDetailScreen(
            device = selected,
            onBack = { selectedAddress = null },
            onDisconnect = { viewModel.disconnect(selected.address) },
            onReconnect = { viewModel.reconnect(selected.address) },
        )
        else -> DeviceListScreen(
            devices = devices.values.toList(),
            discovered = discovered,
            scanning = scanning,
            onScan = { if (scanning) viewModel.stopScan() else viewModel.startScan() },
            onConnect = viewModel::connect,
            onSelect = { selectedAddress = it },
            onDisconnect = viewModel::disconnect,
            onReconnect = viewModel::reconnect,
            onOpenSettings = { showAppSettings = true },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSettingsScreen(
    backgroundDisconnectSeconds: Int,
    onBackgroundDisconnectSecondsChange: (Int) -> Unit,
    serverUploadConfig: ServerUploadConfig,
    serverConnectionCheck: MainViewModel.ServerConnectionCheckState,
    onServerUploadEnabledChange: (Boolean) -> Unit,
    onSaveServerUploadConfiguration: (String, String) -> Unit,
    onServerUploadDraftChange: () -> Unit,
    appUpdateState: MainViewModel.AppUpdateState,
    onCheckForUpdate: () -> Unit,
    onDownloadUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onBack: () -> Unit,
) {
    LaunchedEffect(Unit) { onCheckForUpdate() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onBack) },
                title = { Text("Settings", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SectionTitle("Connections") }
            item {
                BackgroundDisconnectCard(
                    seconds = backgroundDisconnectSeconds,
                    onSecondsChange = onBackgroundDisconnectSecondsChange,
                )
            }
            item {
                ServerUploadCard(
                    config = serverUploadConfig,
                    connectionCheck = serverConnectionCheck,
                    onEnabledChange = onServerUploadEnabledChange,
                    onSave = onSaveServerUploadConfiguration,
                    onDraftChange = onServerUploadDraftChange,
                )
            }
            item { SectionTitle("Application") }
            item {
                AppUpdateCard(
                    state = appUpdateState,
                    onCheck = onCheckForUpdate,
                    onDownload = onDownloadUpdate,
                    onInstall = onInstallUpdate,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceListScreen(
    devices: List<BmsDeviceState>,
    discovered: List<DiscoveredBms>,
    scanning: Boolean,
    onScan: () -> Unit,
    onConnect: (DiscoveredBms) -> Unit,
    onSelect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onReconnect: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val knownAddresses = devices.mapTo(mutableSetOf()) { it.address }
    val orderedDevices = devices.sortedWith(
        compareByDescending<BmsDeviceState> { it.connectionStatus == ConnectionStatus.CONNECTED }
            .thenByDescending { it.lastConnectedAtMillis },
    )
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = "App settings",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (devices.isNotEmpty()) {
                item { SectionTitle("My batteries") }
                items(orderedDevices, key = { it.address }) { device ->
                    ConnectedDeviceCard(device, onSelect, onDisconnect, onReconnect)
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Nearby devices")
                    if (scanning) {
                        Spacer(Modifier.width(10.dp))
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                }
            }
            if (!scanning && discovered.isEmpty()) {
                item {
                    EmptyCard(
                        "No BMS devices found yet",
                        "Make sure no other app is connected, then tap Scan.",
                        "Scan for devices",
                        onScan,
                    )
                }
            }
            items(discovered.filterNot { it.address in knownAddresses }, key = { it.address }) { device ->
                DiscoveredDeviceCard(device, onConnect)
            }
        }
    }
}

@Composable
private fun ConnectedDeviceCard(
    device: BmsDeviceState,
    onSelect: (String) -> Unit,
    onDisconnect: (String) -> Unit,
    onReconnect: (String) -> Unit,
) {
    Card(
        onClick = { onSelect(device.address) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(device.connectionStatus)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(statusLabel(device.connectionStatus), style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    if (device.connectionStatus == ConnectionStatus.DISCONNECTED) "Saved" else "${device.rssi} dBm",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (device.telemetry.updatedAtMillis > 0) {
                LinearProgressIndicator(
                    progress = { device.telemetry.stateOfChargePercent.coerceIn(0, 100) / 100f },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("Charge", "${device.telemetry.stateOfChargePercent}%")
                    Metric("Voltage", format(device.telemetry.packVoltageV, "V"))
                    Metric("Power", format(device.telemetry.powerW, "W"))
                }
            }
            if (device.lastConnectedAtMillis > 0 || device.telemetry.updatedAtMillis > 0) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (device.lastConnectedAtMillis > 0) {
                        TimestampText("Last connected", device.lastConnectedAtMillis)
                    }
                }
            }
            device.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun DiscoveredDeviceCard(device: DiscoveredBms, onConnect: (DiscoveredBms) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(device.name, fontWeight = FontWeight.SemiBold)
                Text("${device.address} · ${device.rssi} dBm", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { onConnect(device) }) { Text("Connect") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceDetailScreen(
    device: BmsDeviceState,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { BackButton(onBack) },
                title = {
                    Column {
                        Text(device.name, fontWeight = FontWeight.SemiBold)
                        Text(statusLabel(device.connectionStatus), style = MaterialTheme.typography.labelMedium)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (device.connectionStatus == ConnectionStatus.DISCONNECTED) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            if (device.telemetry.updatedAtMillis > 0) {
                                "Offline · showing the last saved snapshot from ${formatTimestamp(device.telemetry.updatedAtMillis)}"
                            } else {
                                "Offline · no saved snapshot yet"
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(onClick = onReconnect, modifier = Modifier.align(Alignment.End)) {
                            Text("Reconnect")
                        }
                    }
                }
            }
            TabRow(selectedTabIndex = tab) {
                listOf("Overview", "Cells", "Settings").forEachIndexed { index, label ->
                    Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
                }
            }
            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> OverviewTab(device)
                    1 -> CellsTab(device.telemetry)
                    else -> SettingsTab(
                        settings = device.settings,
                        liveCellCount = device.telemetry.cellCount,
                        connected = device.connectionStatus == ConnectionStatus.CONNECTED,
                    )
                }
            }
            if (device.connectionStatus != ConnectionStatus.DISCONNECTED) {
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(48.dp),
                ) {
                    Text("Disconnect")
                }
            }
        }
    }
}

@Composable
private fun BackButton(onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_arrow_back),
            contentDescription = "Back",
        )
    }
}

@Composable
private fun OverviewTab(device: BmsDeviceState) {
    val t = device.telemetry
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column {
                            Text("State of charge", style = MaterialTheme.typography.labelLarge)
                            Text("${t.stateOfChargePercent}%", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(activityLabel(t), style = MaterialTheme.typography.titleMedium)
                            Text(format(t.powerW, "W"), style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                    LinearProgressIndicator(
                        progress = { t.stateOfChargePercent.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth().height(10.dp),
                    )
                }
            }
        }
        item {
            MetricGrid(
                listOf(
                    "Pack voltage" to format(t.packVoltageV, "V"),
                    "Current" to signedFormat(t.currentA, "A"),
                    "Power" to signedFormat(t.powerW, "W"),
                    "Cell delta" to "${(t.cellDeltaV * 1000).toInt()} mV",
                    "Remaining" to format(t.remainingCapacityAh, "Ah"),
                    "Full capacity" to format(t.fullCapacityAh, "Ah"),
                ),
            )
        }
        item { SectionTitle("Switch state") }
        item {
            MetricGrid(
                listOf(
                    "Charge MOS" to if (t.chargeMosfetOn) "On" else "Off",
                    "Discharge MOS" to if (t.dischargeMosfetOn) "On" else "Off",
                    "Cycles" to t.cycleCount.toString(),
                    "Cells" to t.cellCount.toString(),
                ),
            )
        }
        item { SectionTitle("Temperature sensors") }
        if (t.temperaturesC.isEmpty()) item { Text("Waiting for temperature data…") }
        items(t.temperaturesC.withIndex().toList()) { (index, value) ->
            ValueRow("Sensor ${index + 1}", format(value, "°C"))
        }
        if (device.lastConnectedAtMillis > 0) {
            item { ValueRow("Last connected", formatTimestamp(device.lastConnectedAtMillis)) }
        }
        if (t.updatedAtMillis > 0) {
            item { ValueRow("Last updated", formatTimestamp(t.updatedAtMillis)) }
        }
    }
}

@Composable
private fun CellsTab(telemetry: BmsTelemetry) {
    val cells = telemetry.cellVoltagesV
    val min = cells.minOrNull()
    val max = cells.maxOrNull()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            MetricGrid(
                listOf(
                    "Average" to format(telemetry.averageCellV, "V", 3),
                    "Delta" to "${(telemetry.cellDeltaV * 1000).toInt()} mV",
                    "Lowest" to (min?.let { format(it, "V", 3) } ?: "—"),
                    "Highest" to (max?.let { format(it, "V", 3) } ?: "—"),
                ),
            )
        }
        if (cells.isEmpty()) item { Text("Waiting for cell voltage data…") }
        items(cells.withIndex().toList()) { (index, voltage) ->
            val annotation = when (voltage) {
                min -> "Lowest"
                max -> "Highest"
                else -> null
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Row(Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Cell ${index + 1}", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    if (index + 1 in telemetry.balancingCells) {
                        Text("Balancing", color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(12.dp))
                    }
                    annotation?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium)
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(format(voltage, "V", 3), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private data class SettingRow(val label: String, val value: String?, val explanation: String? = null)

@Composable
private fun SettingsTab(settings: BmsSettings, liveCellCount: Int, connected: Boolean) {
    val cellCount = settings.configuredCellCount ?: liveCellCount
    fun packVoltage(value: Double?) = value?.let {
        if (cellCount > 0) "${format(it, "V")} · ${format(it / cellCount, "V", 3)}/cell" else format(it, "V")
    }
    val values = listOf(
        SettingRow("Hardware version", settings.hardwareVersion),
        SettingRow(
            "Configured cells",
            cellCount.takeIf { it > 0 }?.toString(),
            "Number of cells in series the BMS is set up for.",
        ),
        SettingRow(
            "Design capacity",
            settings.designCapacityAh?.let { format(it, "Ah") },
            "The capacity the charge percentage is calculated against. Should match what the pack really holds.",
        ),
        SettingRow(
            "Cycle capacity",
            settings.cycleCapacityAh?.let { format(it, "Ah") },
            "Only drives the cycle counter: one cycle is counted per this many Ah discharged. " +
                "Doesn't affect the percentage. Usually 80% of design capacity.",
        ),
        SettingRow(
            "Cell voltage at 100%",
            settings.fullCellVoltageV?.let { format(it, "V", 3) },
            "When cells reach this voltage, the BMS resets its charge count to 100%.",
        ),
        SettingRow(
            "Cell voltage at 0%",
            settings.emptyCellVoltageV?.let { format(it, "V", 3) },
            "When cells drop to this voltage, the BMS resets its charge count to 0%.",
        ),
        SettingRow(
            "Cell overvoltage cutoff",
            settings.cellOvervoltageV?.let { format(it, "V", 3) },
            "Charging stops when any cell rises above this.",
        ),
        SettingRow(
            "Cell undervoltage cutoff",
            settings.cellUndervoltageV?.let { format(it, "V", 3) },
            "Discharging stops when any cell drops below this.",
        ),
        SettingRow(
            "Pack overvoltage cutoff",
            packVoltage(settings.packOvervoltageV),
            "Charging stops when the total pack voltage rises above this.",
        ),
        SettingRow(
            "Pack undervoltage cutoff",
            packVoltage(settings.packUndervoltageV),
            "Discharging stops when the total pack voltage drops below this.",
        ),
        SettingRow(
            "Balancing starts",
            settings.balanceStartVoltageV?.let { format(it, "V", 3) },
            "Cells are only balanced above this voltage.",
        ),
        SettingRow(
            "Balancing delta",
            settings.balanceStartDeltaV?.let { "${(it * 1000).toInt()} mV" },
            "Balancing starts when cells differ by more than this.",
        ),
        SettingRow(
            "Charge high temperature",
            settings.chargeOverTemperatureC?.let { format(it, "°C") },
            "Charging stops above this temperature.",
        ),
        SettingRow(
            "Charge low temperature",
            settings.chargeUnderTemperatureC?.let { format(it, "°C") },
            "Charging stops below this temperature.",
        ),
        SettingRow(
            "Discharge high temperature",
            settings.dischargeOverTemperatureC?.let { format(it, "°C") },
            "Discharging stops above this temperature.",
        ),
        SettingRow(
            "Discharge low temperature",
            settings.dischargeUnderTemperatureC?.let { format(it, "°C") },
            "Discharging stops below this temperature.",
        ),
    )
    val advice = if (settings.loaded) settingsAdvice(settings, cellCount) else emptyList()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "Read only · no settings can be changed",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        settings.unavailableReason?.let { reason ->
            item { Text(reason, color = MaterialTheme.colorScheme.error) }
        }
        if (!settings.loaded && settings.unavailableReason == null && connected) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Reading configuration…")
                }
            }
        } else if (!settings.loaded && settings.unavailableReason == null) {
            item {
                Text(
                    "No saved configuration snapshot. Reconnect to read it.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(advice) { AdviceCard(it) }
        items(values) { SettingValueRow(it) }
    }
}

@Composable
private fun AdviceCard(note: SettingsNote) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(note.title, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(note.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun SettingValueRow(row: SettingRow) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(row.label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(row.value ?: "—", fontWeight = FontWeight.SemiBold)
            }
            row.explanation?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun MetricGrid(values: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        values.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) ->
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TimestampText(label: String, timestamp: Long) {
    Text(
        "$label: ${formatTimestamp(timestamp)}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun StatusDot(status: ConnectionStatus) {
    val color = when (status) {
        ConnectionStatus.CONNECTED -> MaterialTheme.colorScheme.primary
        ConnectionStatus.CONNECTING, ConnectionStatus.DISCOVERING -> Color(0xFFF0A020)
        ConnectionStatus.DISCONNECTED -> MaterialTheme.colorScheme.outline
    }
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun AppUpdateCard(
    state: MainViewModel.AppUpdateState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    val busy = state is MainViewModel.AppUpdateState.Checking ||
        state is MainViewModel.AppUpdateState.Downloading
    val status = when (state) {
        MainViewModel.AppUpdateState.Idle -> "Check for a newer APK."
        MainViewModel.AppUpdateState.Checking -> "Checking for updates…"
        is MainViewModel.AppUpdateState.UpToDate -> "You have the latest version."
        is MainViewModel.AppUpdateState.Available -> "Version ${state.release.versionName} is available."
        is MainViewModel.AppUpdateState.Downloading -> "Downloading and verifying version ${state.release.versionName}…"
        is MainViewModel.AppUpdateState.ReadyToInstall -> "Version ${state.release.versionName} is downloaded and verified."
        is MainViewModel.AppUpdateState.Error -> state.message
    }
    val action = when (state) {
        is MainViewModel.AppUpdateState.Available -> "Download update" to onDownload
        is MainViewModel.AppUpdateState.ReadyToInstall -> "Install update" to onInstall
        else -> "Check for updates" to onCheck
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("App updates", fontWeight = FontWeight.SemiBold)
            Text(
                "Installed version ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state is MainViewModel.AppUpdateState.Error) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Button(
                onClick = action.second,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(action.first)
            }
            Text(
                "Android may ask you to allow installs from Battery Monitor before showing the installer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ServerUploadCard(
    config: ServerUploadConfig,
    connectionCheck: MainViewModel.ServerConnectionCheckState,
    onEnabledChange: (Boolean) -> Unit,
    onSave: (String, String) -> Unit,
    onDraftChange: () -> Unit,
) {
    var serverUrl by remember(config.serverUrl) { mutableStateOf(config.serverUrl) }
    var apiKey by remember(config.apiKey) { mutableStateOf(config.apiKey) }
    val hasValidUrl = isValidHttpsUrl(serverUrl)

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Store data on server", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Upload battery data to external server",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = config.enabled, onCheckedChange = onEnabledChange)
            }

            if (config.enabled) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = {
                        serverUrl = it
                        onDraftChange()
                    },
                    label = { Text("Server URL") },
                    placeholder = { Text("https://joeke.dev/api/battery-data") },
                    supportingText = {
                        Text(if (serverUrl.isBlank() || hasValidUrl) "HTTPS endpoint" else "Enter a valid HTTPS URL")
                    },
                    isError = serverUrl.isNotBlank() && !hasValidUrl,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = {
                        apiKey = it
                        onDraftChange()
                    },
                    label = { Text("API key") },
                    supportingText = { Text("Sent using the X-Api-Key header") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { onSave(serverUrl, apiKey) },
                    enabled = hasValidUrl && apiKey.isNotBlank() &&
                        connectionCheck != MainViewModel.ServerConnectionCheckState.Checking,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (connectionCheck == MainViewModel.ServerConnectionCheckState.Checking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Checking connection…")
                    } else {
                        Text("Save and test connection")
                    }
                }
                if (connectionCheck is MainViewModel.ServerConnectionCheckState.Complete) {
                    val (message, isError) = connectionCheckMessage(connectionCheck.result)
                    Text(
                        message,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    "While the app is open, each connected BMS uploads on connection and about every 30 seconds.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun connectionCheckMessage(result: ServerConnectionCheckResult): Pair<String, Boolean> = when (result) {
    ServerConnectionCheckResult.Success -> "Connected successfully. The URL and API key are valid." to false
    ServerConnectionCheckResult.InvalidUrl -> "Enter a valid HTTPS endpoint." to true
    ServerConnectionCheckResult.InvalidApiKey -> "Authentication failed. Check the API key." to true
    ServerConnectionCheckResult.NotFound -> "Endpoint not found. Check the server URL." to true
    ServerConnectionCheckResult.RateLimited -> "The server rate limit was reached. Try again shortly." to true
    ServerConnectionCheckResult.NetworkError -> "Could not reach the server. Check the network, URL, and TLS certificate." to true
    is ServerConnectionCheckResult.UnexpectedResponse ->
        "The server returned HTTP ${result.statusCode}." to true
}

@Composable
private fun BackgroundDisconnectCard(seconds: Int, onSecondsChange: (Int) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Background disconnect", fontWeight = FontWeight.SemiBold)
                Text(
                    if (seconds == MainViewModel.NEVER_DISCONNECT) {
                        "Keep connections while Android keeps the app alive"
                    } else {
                        "Disconnect after the app is away for ${timeoutLabel(seconds)}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = { showDialog = true }) { Text(timeoutLabel(seconds)) }
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Background disconnect") },
            text = {
                Column {
                    Text(
                        "Choose how long connections stay active after leaving Battery Monitor.",
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                    MainViewModel.BACKGROUND_TIMEOUT_OPTIONS_SECONDS.forEach { option ->
                        TextButton(
                            onClick = {
                                onSecondsChange(option)
                                showDialog = false
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (option == seconds) "✓  ${timeoutLabel(option)}" else timeoutLabel(option),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun EmptyCard(title: String, body: String, action: String, onAction: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun BlockingMessage(
    title: String,
    body: String,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null && onAction != null) Button(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun JbdTheme(content: @Composable () -> Unit) {
    val scheme = androidx.compose.material3.lightColorScheme(
        primary = Color(0xFF176B52),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFC8F2DF),
        background = Color(0xFFF7F9F4),
        surface = Color.White,
        tertiary = Color(0xFF496A9B),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

private fun bluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

private fun statusLabel(status: ConnectionStatus) = when (status) {
    ConnectionStatus.CONNECTING -> "Connecting…"
    ConnectionStatus.DISCOVERING -> "Preparing connection…"
    ConnectionStatus.CONNECTED -> "Connected"
    ConnectionStatus.DISCONNECTED -> "Disconnected"
}

private fun activityLabel(telemetry: BmsTelemetry): String = when {
    telemetry.currentA > 0.05 -> "Charging"
    telemetry.currentA < -0.05 -> "Discharging"
    else -> "Idle"
}

private fun format(value: Double, unit: String, decimals: Int = 2): String =
    String.format(Locale.getDefault(), "%.${decimals}f %s", value, unit)

private fun signedFormat(value: Double, unit: String): String =
    String.format(Locale.getDefault(), "%+.2f %s", if (abs(value) < 0.005) 0.0 else value, unit)

private fun formatTimestamp(timestamp: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(timestamp))

private fun timeoutLabel(seconds: Int): String = when (seconds) {
    MainViewModel.NEVER_DISCONNECT -> "Never"
    5 -> "5 seconds"
    10 -> "10 seconds"
    30 -> "30 seconds"
    60 -> "1 minute"
    300 -> "5 minutes"
    1_800 -> "30 minutes"
    else -> "$seconds seconds"
}

private fun isValidHttpsUrl(value: String): Boolean = runCatching {
    val uri = URI(value.trim())
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
}.getOrDefault(false)
