package com.marstek.scheduler

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(this)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(context: Context) {
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf<List<DeviceSchedule>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var discoveryPort by remember { mutableStateOf("30000") }

    LaunchedEffect(Unit) {
        devices = DeviceRepository.loadAll(context)
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Marstek Scheduler", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            "Le telephone doit etre connecte au meme Wi-Fi que vos batteries.",
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = discoveryPort,
                onValueChange = { discoveryPort = it.filter(Char::isDigit) },
                label = { Text("Port UDP") },
                modifier = Modifier.width(140.dp)
            )
            Spacer(Modifier.width(12.dp))
            Button(
                enabled = !scanning,
                onClick = {
                    scanning = true
                    statusText = null
                    scope.launch {
                        val port = discoveryPort.toIntOrNull() ?: 30000
                        val found = MarstekUdpClient.discoverDevices(port)
                        val existing = DeviceRepository.loadAll(context)
                        val merged = existing.toMutableList()
                        found.forEach { d ->
                            if (merged.none { it.ip == d.ip }) {
                                merged.add(
                                    DeviceSchedule(
                                        ip = d.ip,
                                        port = port,
                                        label = "${d.deviceModel} (${d.ip})",
                                        bleMac = d.bleMac
                                    )
                                )
                            }
                        }
                        DeviceRepository.saveAll(context, merged)
                        devices = merged
                        statusText = if (found.isEmpty())
                            "Aucune batterie trouvee. Verifiez le Wi-Fi et le port UDP configure dans l'app Marstek."
                        else "Trouve ${found.size} batterie(s)."
                        scanning = false
                    }
                }
            ) {
                Text(if (scanning) "Recherche..." else "Rechercher les batteries")
            }
        }

        statusText?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(12.dp))
        BatteryOptimizationBanner(context)

        Spacer(Modifier.height(12.dp))
        Divider()

        LazyColumn(Modifier.weight(1f)) {
            items(devices, key = { it.ip }) { device ->
                DeviceCard(
                    device = device,
                    onSave = { updated ->
                        scope.launch {
                            DeviceRepository.upsert(context, updated)
                            AlarmScheduler.scheduleAllForDevice(context, updated)
                            devices = DeviceRepository.loadAll(context)
                        }
                    },
                    onDelete = {
                        scope.launch {
                            AlarmScheduler.cancelAllForDevice(context, device.ip)
                            DeviceRepository.remove(context, device.ip)
                            devices = DeviceRepository.loadAll(context)
                        }
                    }
                )
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
fun BatteryOptimizationBanner(context: Context) {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val ignoring = pm.isIgnoringBatteryOptimizations(context.packageName)
    if (!ignoring) {
        Card {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "Pour que les bascules horaires restent fiables, autorisez l'app " +
                        "a s'executer sans restriction de batterie.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                }) {
                    Text("Desactiver l'optimisation batterie")
                }
            }
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (!am.canScheduleExactAlarms()) {
            Spacer(Modifier.height(8.dp))
            Card {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "L'autorisation \"Alarmes et rappels\" est requise pour declencher " +
                            "les bascules a l'heure exacte.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                    }) {
                        Text("Autoriser les alarmes exactes")
                    }
                }
            }
        }
    }
}

@Composable
fun DeviceCard(
    device: DeviceSchedule,
    onSave: (DeviceSchedule) -> Unit,
    onDelete: () -> Unit
) {
    var manualStart by remember { mutableStateOf(device.manualStart) }
    var manualEnd by remember { mutableStateOf(device.manualEnd) }
    var manualPower by remember { mutableStateOf(device.manualPower.toString()) }
    var autoStart by remember { mutableStateOf(device.autoStart) }
    var enabled by remember { mutableStateOf(device.enabled) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(device.label, style = MaterialTheme.typography.titleMedium)
                Switch(checked = enabled, onCheckedChange = { enabled = it })
            }
            Text(device.ip, style = MaterialTheme.typography.bodySmall)

            Spacer(Modifier.height(8.dp))
            Text("Mode Manuel", style = MaterialTheme.typography.labelLarge)
            Row {
                OutlinedTextField(
                    value = manualStart, onValueChange = { manualStart = it },
                    label = { Text("Debut (HH:MM)") }, modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = manualEnd, onValueChange = { manualEnd = it },
                    label = { Text("Fin (HH:MM)") }, modifier = Modifier.weight(1f)
                )
            }
            OutlinedTextField(
                value = manualPower,
                onValueChange = { manualPower = it.filter(Char::isDigit) },
                label = { Text("Puissance (W)") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(8.dp))
            Text("Retour Autoconsommation", style = MaterialTheme.typography.labelLarge)
            OutlinedTextField(
                value = autoStart, onValueChange = { autoStart = it },
                label = { Text("Heure de bascule Auto (HH:MM)") },
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(10.dp))
            Row {
                Button(onClick = {
                    onSave(
                        device.copy(
                            manualStart = manualStart,
                            manualEnd = manualEnd,
                            manualPower = manualPower.toIntOrNull() ?: device.manualPower,
                            autoStart = autoStart,
                            enabled = enabled
                        )
                    )
                }) { Text("Enregistrer & planifier") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onDelete) { Text("Supprimer") }
            }
        }
    }
}
