package se.ev3scan

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.Locale
import kotlinx.coroutines.launch

private val Green = Color(0xFF2BD47D)
private val Orange = Color(0xFFFFA726)
private val Red = Color(0xFFEF5350)
private val Blue = Color(0xFF4FC3F7)
private val Muted = Color(0xFF9AA4AE)

class MainActivity : ComponentActivity() {
    private val vm: ScannerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Green, secondary = Blue)) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF101418)) {
                    App(vm, ::hasPermissions)
                }
            }
        }
    }

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    fun hasPermissions(): Array<String> = requiredPermissions().filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }.toTypedArray()
}

private fun f(v: Double?, dec: Int = 1, unit: String = ""): String =
    if (v == null) "–" else String.format(Locale.US, "%.${dec}f", v) + if (unit.isNotEmpty()) " $unit" else ""

private fun i(v: Int?, unit: String = ""): String =
    if (v == null) "–" else "$v" + if (unit.isNotEmpty()) " $unit" else ""

@Composable
fun App(vm: ScannerViewModel, missingPermissions: () -> Array<String>) {
    val s by vm.state.collectAsStateWithLifecycle()
    var permsOk by remember { mutableStateOf(missingPermissions().isEmpty()) }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permsOk = missingPermissions().isEmpty() }

    val enableBt = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Header(s, onDisconnect = vm::disconnect)
        if (s.conn == ConnState.CONNECTED || s.conn == ConnState.INITIALIZING) {
            ConnectedScreen(vm, s)
        } else {
            ConnectScreen(
                s = s,
                permsOk = permsOk,
                requestPerms = { permLauncher.launch(missingPermissions()) },
                onScan = {
                    if (!vm.isBluetoothOn()) {
                        try { enableBt.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: SecurityException) { }
                    } else vm.startScan()
                },
                onConnect = vm::connect,
                onConnectLast = vm::connectLast,
                vm = vm,
            )
        }
    }
}

@Composable
fun Header(s: UiState, onDisconnect: () -> Unit) {
    val dot = when (s.conn) {
        ConnState.CONNECTED -> if (s.errorStreak >= 3) Orange else Green
        ConnState.IDLE -> Muted
        else -> Blue
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(10.dp).height(10.dp).background(dot, RoundedCornerShape(5.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("EV3 Scan", fontWeight = FontWeight.Bold, fontSize = 20.sp)
            Text(s.status, color = Muted, fontSize = 13.sp, maxLines = 2)
        }
        if (s.conn == ConnState.CONNECTED || s.conn == ConnState.INITIALIZING || s.conn == ConnState.CONNECTING) {
            TextButton(onClick = onDisconnect) { Text("Koppla från") }
        }
    }
}

// ---------------- Anslutningsskärm ----------------

@Composable
fun ConnectScreen(
    s: UiState,
    permsOk: Boolean,
    requestPerms: () -> Unit,
    onScan: () -> Unit,
    onConnect: (String) -> Unit,
    onConnectLast: () -> Unit,
    vm: ScannerViewModel,
) {
    var showLog by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "1. Sätt UCSI-2100 i OBD-uttaget (under ratten, vänster sida).\n" +
                "2. Slå på bilen så den är i READY (eller laddar).\n" +
                "3. Sök och välj adaptern.\n\nParkoppla INTE adaptern i Androids Bluetooth-inställningar – appen ansluter själv.",
            color = Muted, fontSize = 14.sp,
        )
        Spacer(Modifier.height(16.dp))

        if (!permsOk) {
            Button(onClick = requestPerms, modifier = Modifier.fillMaxWidth()) { Text("Ge Bluetooth-behörighet") }
            return@Column
        }

        if (s.conn == ConnState.CONNECTING) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.width(24.dp).height(24.dp))
                Spacer(Modifier.width(12.dp))
                Text("Ansluter …")
            }
            Spacer(Modifier.height(12.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.lastAddress != null) {
                Button(onClick = onConnectLast, enabled = s.conn == ConnState.IDLE) { Text("Anslut senaste") }
            }
            OutlinedButton(onClick = onScan, enabled = s.conn != ConnState.CONNECTING) {
                Text(if (s.conn == ConnState.SCANNING) "Söker …" else "Sök adapter")
            }
            TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Dölj logg" else "Logg") }
        }
        Spacer(Modifier.height(12.dp))

        if (showLog) {
            LogView(vm, s, Modifier.weight(1f))
        } else {
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(s.devices, key = { it.address }) { d ->
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onConnect(d.address) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (d.looksLikeObd) Color(0xFF1E3A2B) else Color(0xFF1A2027)
                        ),
                    ) {
                        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(d.name ?: "(okänt namn)", fontWeight = FontWeight.SemiBold)
                                Text(d.address, color = Muted, fontSize = 12.sp)
                            }
                            Text("${d.rssi} dBm", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// ---------------- Ansluten ----------------

@Composable
fun ConnectedScreen(vm: ScannerViewModel, s: UiState) {
    var tab by remember { mutableIntStateOf(0) }
    DisposableEffect(tab) {
        vm.cellsVisible = tab == 1
        onDispose { vm.cellsVisible = false }
    }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab, containerColor = Color(0xFF101418)) {
            listOf("Batteri", "Celler", "Logg").forEachIndexed { idx, title ->
                Tab(selected = tab == idx, onClick = { tab = idx }, text = { Text(title) })
            }
        }
        when (tab) {
            0 -> BatteryTab(vm, s)
            1 -> CellsTab(s)
            else -> LogView(vm, s, Modifier.weight(1f).padding(12.dp))
        }
    }
}

@Composable
fun BatteryTab(vm: ScannerViewModel, s: UiState) {
    val m = s.main
    val h = s.health
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (m == null) {
            Text("Väntar på data från batteriet …", color = Muted)
            return@Column
        }

        // Stor SoC + effekt
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2027))) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                val soc = h?.socDisplay ?: m.socBms
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(f(soc, 1), fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Green)
                    Text(" %", fontSize = 24.sp, color = Green, modifier = Modifier.padding(bottom = 10.dp))
                    Spacer(Modifier.weight(1f))
                    val p = m.powerKw
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            f(p?.let { -it }, 1, "kW"),
                            fontSize = 30.sp, fontWeight = FontWeight.Bold,
                            color = when {
                                p == null -> Muted
                                p < -0.3 -> Green
                                p > 0.3 -> Orange
                                else -> Muted
                            },
                        )
                        Text(
                            when {
                                p == null -> ""
                                p < -0.3 -> if (s.isCharging == true) "laddar" else "regenererar"
                                p > 0.3 -> "förbrukar"
                                else -> "vila"
                            },
                            color = Muted, fontSize = 13.sp,
                        )
                    }
                }
                LinearProgressIndicator(
                    progress = { ((soc ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = Green,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Display-SoC ${f(h?.socDisplay)} %  ·  BMS-SoC ${f(m.socBms)} %",
                    color = Muted, fontSize = 12.sp,
                )
            }
        }

        if (s.isCharging == true || s.charger.socketConnected == true) ChargingSection(s, m)

        Section("Batterihälsa") {
            Row2("SoH", f(h?.sohPct, 1, "%"))
            Row2("Kvarvarande energi (BMS)", f(h?.remainingKwh, 1, "kWh"))
        }

        Section("Ström & spänning") {
            Row2("Spänning", f(m.voltageV, 1, "V"))
            Row2("Ström", f(m.currentA, 1, "A"))
            Row2("Max laddeffekt nu", f(m.availChargeKw, 1, "kW"))
            Row2("Max uteffekt nu", f(m.availDischargeKw, 1, "kW"))
            Row2("Topp laddning (session)", f(s.peakChargeKw, 1, "kW"))
            Row2("Topp uttag (session)", f(s.peakDischargeKw, 1, "kW"))
            Row2("12V-batteri", f(m.auxV, 1, "V"))
            TextButton(onClick = vm::resetPeaks) { Text("Nollställ toppar") }
        }

        Section("Temperaturer") {
            Row2("Batteri max / min", "${i(m.tempMax)} / ${i(m.tempMin, "°C")}")
            Row2("Inlopp (kylvätska)", i(m.inletTemp, "°C"))
            Row2("Moduler", if (m.moduleTemps.isEmpty()) "–" else m.moduleTemps.joinToString(" · ") + " °C")
        }

        Section("Celler") {
            Row2("Högsta cell", "${f(m.cellMaxV, 2, "V")}  (#${i(m.cellMaxNo)})")
            Row2("Lägsta cell", "${f(m.cellMinV, 2, "V")}  (#${i(m.cellMinNo)})")
            val delta = m.cellDeltaMv
            Row2(
                "Skillnad", f(delta, 0, "mV"),
                valueColor = when {
                    delta == null -> Color.White
                    delta <= 40 -> Green
                    delta <= 100 -> Orange
                    else -> Red
                },
            )
        }

        if (!(s.isCharging == true || s.charger.socketConnected == true)) ChargingSection(s, m)

        Section("Status") {
            Row2("Laddar", yn(s.isCharging))
            Row2("Laddkabel ansluten", yn(s.charger.socketConnected))
            Row2("Huvudrelä", yn(s.charger.mainRelay ?: m.mainRelay))
            Row2("Motorvarvtal", i(m.motorRpm, "rpm"))
            Row2("Isolationsresistans", i(m.isolationKohm, "kΩ"))
        }

        Section("Livstid") {
            Row2("Totalt laddat", f(m.cumChargeKwh, 1, "kWh"))
            Row2("Totalt förbrukat", f(m.cumDischargeKwh, 1, "kWh"))
            Row2("Laddat Ah / urladdat Ah", "${f(m.cumChargeAh, 0)} / ${f(m.cumDischargeAh, 0)}")
            Row2("Drifttid", f(m.operatingHours, 0, "h"))
        }

        Text("Adapter: ${s.elmVersion ?: "–"}", color = Muted, fontSize = 12.sp)
    }
}

@Composable
fun ChargingSection(s: UiState, m: BatteryMain) {
    val c = s.charger
    Section("Laddning") {
        if (!s.vcmsOk) {
            Text("Laddstyrenheten (744) svarar inte – visar bara batteriets värden.", color = Muted, fontSize = 12.sp)
        }
        val type = when {
            c.dcCharging == true -> "DC (snabbladdning)"
            c.acCharging == true -> "AC"
            s.isCharging == true -> "Ja"
            else -> "Nej"
        }
        Row2("Laddar", type, valueColor = if (s.isCharging == true) Green else Color.White)
        Row2("Effekt in i batteriet", f(m.powerKw?.let { maxOf(0.0, -it) }, 1, "kW"))
        Row2("Beräknad laddeffekt (VCMS)", f(c.estimatedPowerW?.takeIf { it > 0 }?.div(1000.0), 1, "kW"))
        Row2("Ombordladdare AC", "${f(c.obcAcCurrent, 1, "A")} · ${i(c.obcAcVoltage, "V")}")
        Row2("Ombordladdare DC", "${f(c.obcDcCurrent, 1, "A")} · ${f(c.obcDcVoltage, 0, "V")}")
        Row2("Laddkabel (CP)", "${f(c.cpVoltage, 1, "V")} · ${f(c.cpDuty, 0, "%")}")
        val mins = listOfNotNull(c.acMinutesSincePlug, c.dcMinutesSincePlug).maxOrNull()
        Row2("Tid sedan inkoppling", i(mins, "min"))
        Row2("Laddintag AC / DC", "${i(c.acInletT)} / ${i(c.dcInletT1)} °C")
    }
}

private fun yn(b: Boolean?) = when (b) { true -> "Ja"; false -> "Nej"; null -> "–" }

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1A2027))) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(title, fontWeight = FontWeight.Bold, color = Blue, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
fun Row2(label: String, value: String, valueColor: Color = Color.White) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = Muted, modifier = Modifier.weight(1f), fontSize = 14.sp)
        Text(value, color = valueColor, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun CellsTab(s: UiState) {
    val cells = s.cells
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        if (cells.isEmpty()) {
            Text("Läser cellspänningar …", color = Muted)
            return@Column
        }
        val valid = cells.filter { it > 0.5 }
        val max = valid.maxOrNull() ?: 0.0
        val min = valid.minOrNull() ?: 0.0
        Text(
            "${valid.size} celler  ·  max ${f(max, 2)} V  ·  min ${f(min, 2)} V  ·  Δ ${f((max - min) * 1000, 0)} mV",
            color = Muted, fontSize = 13.sp,
        )
        Spacer(Modifier.height(8.dp))
        LazyVerticalGrid(
            columns = GridCells.Adaptive(68.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(cells) { idx, v ->
                val bg = when {
                    v < 0.5 -> Color(0xFF15191E)
                    v == max -> Color(0xFF1E3A2B)
                    v == min -> Color(0xFF3A1E1E)
                    else -> Color(0xFF1A2027)
                }
                Column(
                    Modifier.background(bg, RoundedCornerShape(6.dp)).padding(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("#${idx + 1}", color = Muted, fontSize = 10.sp)
                    Text(f(v, 2), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun LogView(vm: ScannerViewModel, s: UiState, modifier: Modifier = Modifier) {
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(s.log.size) {
        if (s.log.isNotEmpty()) listState.scrollToItem(s.log.size - 1)
    }
    Column(modifier) {
        if (s.conn == ConnState.CONNECTED) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Kommando, t.ex. 220101 eller ATRV") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { vm.sendManual(input) }),
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { vm.sendManual(input) }) { Text("Skicka") }
            }
        }
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        var dumping by remember { mutableStateOf(false) }
        Row {
            TextButton(onClick = vm::clearLog) { Text("Rensa") }
            if (s.conn == ConnState.CONNECTED) {
                TextButton(enabled = !dumping, onClick = {
                    dumping = true
                    scope.launch {
                        val text = vm.dumpRaw()
                        dumping = false
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "EV3 Scan rådata")
                            putExtra(Intent.EXTRA_TEXT, text)
                        }
                        ctx.startActivity(Intent.createChooser(send, "Dela rådata"))
                    }
                }) { Text(if (dumping) "Läser …" else "Dela rådata") }
            }
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(s.log) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Color(0xFFCFD8DC))
            }
        }
    }
}
