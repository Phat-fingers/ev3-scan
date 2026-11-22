package se.ev3scan

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ConnState { IDLE, SCANNING, CONNECTING, INITIALIZING, CONNECTED }

data class FoundDevice(val address: String, val name: String?, val rssi: Int) {
    val looksLikeObd: Boolean
        get() = name?.let { n ->
            listOf("UNICAR", "UCSI", "OBD", "ELM", "VLINK", "V-LINK", "IOS-VLINK", "VEEPEAK", "KONNWEI", "WGSOFT")
                .any { n.uppercase().contains(it) }
        } ?: false
}

data class UiState(
    val conn: ConnState = ConnState.IDLE,
    val status: String = "Inte ansluten",
    val devices: List<FoundDevice> = emptyList(),
    val lastAddress: String? = null,
    val elmVersion: String? = null,
    val main: BatteryMain? = null,
    val health: BatteryHealth? = null,
    val cells: List<Double> = emptyList(),
    val charger: ChargerInfo = ChargerInfo(),
    val vcmsOk: Boolean = false,
    val peakDischargeKw: Double = 0.0,
    val peakChargeKw: Double = 0.0,
    val lastUpdate: Long = 0,
    val errorStreak: Int = 0,
    val log: List<String> = emptyList(),
) {
    /** Laddar: VCMS-flaggor i första hand, annars negativ batteriström med stillastående motor. */
    val isCharging: Boolean?
        get() {
            if (charger.acCharging == true || charger.dcCharging == true) return true
            val m = main ?: return null
            val cur = m.currentA ?: return null
            if (cur < -1.0 && (m.motorRpm ?: 0) == 0) return true
            if (charger.acCharging == false && charger.dcCharging == false) return false
            return cur < -1.0 && (m.motorRpm ?: 0) == 0
        }
}

@SuppressLint("MissingPermission")
class ScannerViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val prefs = app.getSharedPreferences("ev3scan", Context.MODE_PRIVATE)
    private val btManager = app.getSystemService(BluetoothManager::class.java)
    private var transport: BleElmTransport? = null
    private var pollJob: Job? = null
    private var scanStopJob: Job? = null
    private var currentHeader: String? = null
    private val busMutex = Mutex()

    /** Sätts av UI:t när fliken Celler syns, så att vi bara läser celler då. */
    @Volatile var cellsVisible = false

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    init {
        _state.update { it.copy(lastAddress = prefs.getString("last_address", null)) }
    }

    fun log(msg: String) {
        val line = "${timeFmt.format(Date())}  $msg"
        _state.update { s -> s.copy(log = (s.log + line).takeLast(400)) }
    }

    fun clearLog() = _state.update { it.copy(log = emptyList()) }

    fun isBluetoothOn(): Boolean = btManager?.adapter?.isEnabled == true

    // ---------- Skanning ----------

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val dev = result.device
            val name = result.scanRecord?.deviceName ?: try { dev.name } catch (_: SecurityException) { null }
            val found = FoundDevice(dev.address, name, result.rssi)
            _state.update { s ->
                val others = s.devices.filter { it.address != found.address }
                s.copy(devices = (others + found).sortedWith(
                    compareByDescending<FoundDevice> { it.looksLikeObd }
                        .thenByDescending { it.name != null }
                        .thenByDescending { it.rssi }
                ))
            }
        }

        override fun onScanFailed(errorCode: Int) {
            log("Skanning misslyckades (kod $errorCode)")
            _state.update { it.copy(conn = ConnState.IDLE, status = "Skanning misslyckades ($errorCode)") }
        }
    }

    fun startScan() {
        val scanner = btManager?.adapter?.bluetoothLeScanner ?: run {
            _state.update { it.copy(status = "Bluetooth är avstängt") }
            return
        }
        _state.update { it.copy(conn = ConnState.SCANNING, status = "Söker efter adaptern …", devices = emptyList()) }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(null, settings, scanCallback)
        } catch (e: Exception) {
            log("Kunde inte starta skanning: ${e.message}")
        }
        scanStopJob?.cancel()
        scanStopJob = viewModelScope.launch {
            delay(12_000)
            stopScan()
        }
    }

    fun stopScan() {
        try { btManager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: Exception) { }
        scanStopJob?.cancel()
        _state.update {
            if (it.conn == ConnState.SCANNING) it.copy(conn = ConnState.IDLE, status = "Välj adapter i listan") else it
        }
    }

    // ---------- Anslutning ----------

    fun connectLast() {
        val addr = _state.value.lastAddress ?: return
        connect(addr)
    }

    fun connect(address: String) {
        stopScan()
        val adapter = btManager?.adapter ?: return
        val device: BluetoothDevice = try { adapter.getRemoteDevice(address) } catch (e: Exception) {
            log("Ogiltig adress $address"); return
        }
        pollJob?.cancel()
        transport?.close()
        currentHeader = null
        val t = BleElmTransport(getApplication<Application>(), ::log) {
            log("Anslutningen bröts")
            pollJob?.cancel()
            _state.update { it.copy(conn = ConnState.IDLE, status = "Anslutningen bröts") }
        }
        transport = t
        _state.update { it.copy(conn = ConnState.CONNECTING, status = "Ansluter …") }

        pollJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                t.connect(device)
                prefs.edit().putString("last_address", address).apply()
                _state.update { it.copy(lastAddress = address, conn = ConnState.INITIALIZING, status = "Initierar adaptern …") }
                initElm(t)
                _state.update { it.copy(conn = ConnState.CONNECTED, status = "Ansluten") }
                pollLoop(t)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log("Fel: ${e.message}")
                t.close()
                _state.update { it.copy(conn = ConnState.IDLE, status = e.message ?: "Fel") }
            }
        }
    }

    fun disconnect() {
        pollJob?.cancel()
        transport?.close()
        transport = null
        _state.update { it.copy(conn = ConnState.IDLE, status = "Frånkopplad") }
    }

    private suspend fun cmd(t: BleElmTransport, c: String, timeout: Long = 2_500, quiet: Boolean = false): String {
        val r = t.send(c, timeout)
        if (!quiet) log("> $c\n${r.trim().replace("\r", "\n")}")
        return r
    }

    private suspend fun initElm(t: BleElmTransport) {
        val z = try { cmd(t, "ATZ", 4_000) } catch (e: ElmException) {
            // Vissa adaptrar svarar inte på ATZ via BLE – försök fortsätta
            log("ATZ: ${e.message}"); ""
        }
        delay(500)
        val ver = ElmParser.cleanLines(z).firstOrNull { it.contains("ELM") || it.contains("V") }
        cmd(t, "ATE0")
        cmd(t, "ATL0")
        cmd(t, "ATS0")
        cmd(t, "ATH1")
        cmd(t, "ATAT1")
        cmd(t, "ATSP6")
        val info = try { ElmParser.cleanLines(cmd(t, "ATI")).firstOrNull() } catch (_: Exception) { null }
        try { cmd(t, "ATRV") } catch (_: Exception) { }
        _state.update { it.copy(elmVersion = info ?: ver) }
        setHeader(t, Ev3Decoder.BMS_TX, Ev3Decoder.BMS_RX)
    }

    private suspend fun setHeader(t: BleElmTransport, tx: String, rx: String) {
        if (currentHeader == tx) return
        cmd(t, "ATSH$tx")
        cmd(t, "ATCRA$rx")
        currentHeader = tx
    }

    private suspend fun rawDid(t: BleElmTransport, tx: String, rx: String, did: Int): String =
        busMutex.withLock {
            setHeader(t, tx, rx)
            t.send("22%04X".format(did), 3_000)
        }

    private suspend fun readDid(
        t: BleElmTransport, did: Int,
        tx: String = Ev3Decoder.BMS_TX, rx: String = Ev3Decoder.BMS_RX, quiet: Boolean = false,
    ): ByteArray {
        val raw = rawDid(t, tx, rx, did)
        return try {
            ElmParser.udsPayload(raw, did, rx)
        } catch (e: ElmException) {
            if (!quiet) log("> $tx 22%04X\n${raw.trim()}".format(did))
            throw e
        }
    }

    private suspend fun pollVcms(t: BleElmTransport, full: Boolean) {
        var c = _state.value.charger
        var ok = false
        try { c = VcmsDecoder.applyE001(c, readDid(t, VcmsDecoder.DID_E001, VcmsDecoder.TX, VcmsDecoder.RX, true)); ok = true } catch (_: ElmException) { }
        try { c = VcmsDecoder.applyE004(c, readDid(t, VcmsDecoder.DID_E004, VcmsDecoder.TX, VcmsDecoder.RX, true)); ok = true } catch (_: ElmException) { }
        if (full) {
            try { c = VcmsDecoder.applyE003(c, readDid(t, VcmsDecoder.DID_E003, VcmsDecoder.TX, VcmsDecoder.RX, true)) } catch (_: ElmException) { }
        }
        if (ok != _state.value.vcmsOk) log(if (ok) "Laddstyrenheten (744) svarar" else "Laddstyrenheten (744) svarar inte")
        _state.update { it.copy(charger = c, vcmsOk = ok) }
    }

    /** Läser alla relevanta DID:ar och returnerar råsvaren som text att dela. */
    suspend fun dumpRaw(): String {
        val t = transport ?: return "Inte ansluten"
        val sb = StringBuilder()
        sb.append("EV3 Scan rådump ").append(Date()).append('\n')
        sb.append("Adapter: ").append(_state.value.elmVersion).append('\n')
        val m = _state.value.main
        sb.append("Tolkat: SoC=${m?.socBms} I=${m?.currentA}A U=${m?.voltageV}V rpm=${m?.motorRpm} laddar=${_state.value.isCharging}\n\n")
        val list = listOf(
            Triple("7E4", "7EC", 0x0101), Triple("7E4", "7EC", 0x0105), Triple("7E4", "7EC", 0x0106),
            Triple("744", "74C", 0xE001), Triple("744", "74C", 0xE002),
            Triple("744", "74C", 0xE003), Triple("744", "74C", 0xE004),
        )
        for ((tx, rx, did) in list) {
            val raw = try { rawDid(t, tx, rx, did) } catch (e: Exception) { "FEL: ${e.message}" }
            sb.append("[$tx 22%04X]\n".format(did)).append(raw.trim().replace("\r", "\n")).append("\n\n")
        }
        log("Rådump klar (${list.size} DID:ar)")
        return sb.toString()
    }

    private suspend fun pollLoop(t: BleElmTransport) {
        var i = 0
        var firstMainLogged = false
        var firstHealthLogged = false
        while (kotlin.coroutines.coroutineContext.isActive) {
            try {
                val d = readDid(t, Ev3Decoder.DID_MAIN)
                if (!firstMainLogged) { log("220101 → ${d.toHex()}"); firstMainLogged = true }
                val m = Ev3Decoder.decodeMain(d)
                _state.update { s ->
                    val p = m.powerKw ?: 0.0
                    s.copy(
                        main = m,
                        lastUpdate = System.currentTimeMillis(),
                        errorStreak = 0,
                        status = "Ansluten",
                        peakDischargeKw = maxOf(s.peakDischargeKw, p),
                        peakChargeKw = maxOf(s.peakChargeKw, -p),
                    )
                }

                if (i % 5 == 0) {
                    val h = readDid(t, Ev3Decoder.DID_HEALTH)
                    if (!firstHealthLogged) { log("220105 → ${h.toHex()}"); firstHealthLogged = true }
                    _state.update { it.copy(health = Ev3Decoder.decodeHealth(h)) }
                }

                if (i % 3 == 1) pollVcms(t, full = i % 15 == 1)

                if (cellsVisible && i % 4 == 0) {
                    val all = ArrayList<Double>()
                    for (did in Ev3Decoder.DID_CELLS) {
                        try {
                            all.addAll(Ev3Decoder.decodeCells(readDid(t, did)))
                        } catch (e: ElmException) {
                            break // större batteri saknar inte DID, mindre kan sakna de sista
                        }
                    }
                    // Ta bort celler som inte finns (0 V) i slutet
                    val trimmed = all.dropLastWhile { it < 0.5 }
                    _state.update { it.copy(cells = trimmed) }
                }
                i++
                delay(250)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: ElmException) {
                val streak = _state.value.errorStreak + 1
                _state.update {
                    it.copy(
                        errorStreak = streak,
                        status = if (streak >= 3) "Inget svar från batteriet – är bilen påslagen (READY eller laddar)?" else it.status,
                    )
                }
                if (streak <= 3 || streak % 10 == 0) log("Läsfel: ${e.message}")
                if (e.message?.contains("frånkopplad") == true) throw e
                delay(if (streak >= 3) 2_000 else 500)
            }
        }
    }

    /** Manuellt kommando från Logg-fliken. */
    fun sendManual(c: String) {
        val t = transport ?: run { log("Inte ansluten"); return }
        val command = c.trim().uppercase()
        if (command.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                busMutex.withLock {
                    if (command.startsWith("ATSH")) currentHeader = command.removePrefix("ATSH")
                    cmd(t, command, 5_000)
                }
            } catch (e: Exception) {
                log("Fel: ${e.message}")
            }
        }
    }

    fun resetPeaks() = _state.update { it.copy(peakChargeKw = 0.0, peakDischargeKw = 0.0) }

    override fun onCleared() {
        stopScan()
        pollJob?.cancel()
        transport?.close()
        super.onCleared()
    }
}
