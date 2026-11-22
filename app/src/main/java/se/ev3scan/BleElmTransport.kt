package se.ev3scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

/**
 * Seriell "UART över BLE" mot en ELM327-kompatibel adapter (t.ex. UniCarScan UCSI-2100).
 * Hittar automatiskt ett notify- och ett write-characteristic i adapterns egen tjänst.
 */
@SuppressLint("MissingPermission")
class BleElmTransport(
    private val context: Context,
    private val log: (String) -> Unit,
    private val onDisconnected: () -> Unit,
) {
    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var mtu = 23

    private val ioMutex = Mutex()
    private val buffer = StringBuilder()
    private val lock = Any()

    @Volatile private var pendingResponse: CompletableDeferred<String>? = null
    @Volatile private var pendingConnect: CompletableDeferred<Boolean>? = null
    @Volatile private var pendingServices: CompletableDeferred<Boolean>? = null
    @Volatile private var pendingMtu: CompletableDeferred<Int>? = null
    @Volatile private var pendingDescriptor: CompletableDeferred<Boolean>? = null
    @Volatile private var pendingWrite: CompletableDeferred<Boolean>? = null
    @Volatile private var userClosed = false

    private val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val standardServices = setOf(0x1800, 0x1801, 0x180A, 0x180F, 0x1805)

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                pendingConnect?.complete(true)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                pendingConnect?.complete(false)
                pendingResponse?.completeExceptionally(ElmException("Bluetooth frånkopplad"))
                if (!userClosed) onDisconnected()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pendingServices?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            pendingMtu?.complete(if (status == BluetoothGatt.GATT_SUCCESS) newMtu else 23)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pendingDescriptor?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingWrite?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handleIncoming(value)
        }

        // Android 12 och äldre
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                @Suppress("DEPRECATION")
                handleIncoming(c.value ?: return)
            }
        }
    }

    private fun handleIncoming(bytes: ByteArray) {
        val text = String(bytes, Charsets.ISO_8859_1)
        synchronized(lock) {
            buffer.append(text)
            if (buffer.contains('>')) {
                val resp = buffer.toString()
                buffer.clear()
                pendingResponse?.complete(resp)
            }
        }
    }

    suspend fun connect(device: BluetoothDevice) {
        userClosed = false
        log("Ansluter till ${device.name ?: device.address} …")
        val conn = CompletableDeferred<Boolean>().also { pendingConnect = it }
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        val ok = try {
            withTimeout(15_000) { conn.await() }
        } catch (e: TimeoutCancellationException) {
            false
        }
        if (!ok) {
            close()
            throw ElmException("Kunde inte ansluta (timeout). Sitter adaptern i och är bilen påslagen?")
        }
        log("Ansluten. Förhandlar MTU …")

        val g = gatt ?: throw ElmException("GATT saknas")
        val mtuDef = CompletableDeferred<Int>().also { pendingMtu = it }
        if (g.requestMtu(185)) {
            mtu = try { withTimeout(3_000) { mtuDef.await() } } catch (e: TimeoutCancellationException) { 23 }
        }
        log("MTU = $mtu")

        delay(300)
        val svc = CompletableDeferred<Boolean>().also { pendingServices = it }
        g.discoverServices()
        val found = try { withTimeout(10_000) { svc.await() } } catch (e: TimeoutCancellationException) { false }
        if (!found) throw ElmException("Hittade inga Bluetooth-tjänster")

        pickCharacteristics(g)
        val rx = rxChar ?: throw ElmException("Hittade ingen dataport i adaptern (se Logg)")
        enableNotifications(g, rx)
        log("Klar: RX=${rx.uuid.short()} TX=${txChar?.uuid?.short()}")
    }

    private fun UUID.short(): String {
        val s = toString()
        return if (s.endsWith("-0000-1000-8000-00805f9b34fb")) s.substring(4, 8).uppercase() else s
    }

    private fun shortId(uuid: UUID): Int? {
        val s = uuid.toString()
        return if (s.endsWith("-0000-1000-8000-00805f9b34fb")) s.substring(4, 8).toInt(16) else null
    }

    private fun pickCharacteristics(g: BluetoothGatt) {
        data class Cand(val score: Int, val rx: BluetoothGattCharacteristic, val tx: BluetoothGattCharacteristic)
        val cands = mutableListOf<Cand>()
        for (s in g.services) {
            val sid = shortId(s.uuid)
            log("Tjänst ${s.uuid.short()}")
            s.characteristics.forEach { c ->
                val p = c.properties
                val flags = buildList {
                    if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("R")
                    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("W")
                    if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WnR")
                    if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("N")
                    if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("I")
                }
                log("   char ${c.uuid.short()} [${flags.joinToString(",")}]")
            }
            if (sid != null && sid in standardServices) continue
            val notif = s.characteristics.filter {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            }
            val write = s.characteristics.filter {
                it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            }
            if (notif.isEmpty() || write.isEmpty()) continue
            val score = when (sid) {
                0xFFF0, 0xFFE0, 0x18F0 -> 10
                else -> if (s.uuid.toString().startsWith("6e400001")) 9 else 1
            }
            // Föredra olika characteristics för RX och TX om möjligt
            val rx = notif.first()
            val tx = write.firstOrNull { it.uuid != rx.uuid } ?: write.first()
            cands.add(Cand(score, rx, tx))
        }
        val best = cands.maxByOrNull { it.score } ?: return
        rxChar = best.rx
        txChar = best.tx
    }

    private suspend fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        g.setCharacteristicNotification(c, true)
        val desc = c.getDescriptor(cccdUuid) ?: run {
            log("Ingen CCCD – fortsätter ändå")
            return
        }
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        val done = CompletableDeferred<Boolean>().also { pendingDescriptor = it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(desc, value)
        } else {
            @Suppress("DEPRECATION")
            desc.value = value
            @Suppress("DEPRECATION")
            g.writeDescriptor(desc)
        }
        try { withTimeout(3_000) { done.await() } } catch (e: TimeoutCancellationException) {
            log("CCCD-svar uteblev – fortsätter ändå")
        }
    }

    private suspend fun writeRaw(bytes: ByteArray) {
        val g = gatt ?: throw ElmException("Inte ansluten")
        val c = txChar ?: throw ElmException("Ingen skrivport")
        val noResp = c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 &&
            c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0
        val type = if (noResp) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val chunk = (mtu - 3).coerceAtLeast(20)
        var off = 0
        while (off < bytes.size) {
            val part = bytes.copyOfRange(off, minOf(bytes.size, off + chunk))
            val done = CompletableDeferred<Boolean>().also { pendingWrite = it }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, part, type)
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = part
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
            try { withTimeout(2_000) { done.await() } } catch (_: TimeoutCancellationException) { }
            off += part.size
        }
    }

    /** Skickar ett kommando och väntar på ELM-prompten '>'. */
    suspend fun send(cmd: String, timeoutMs: Long = 2_500): String = ioMutex.withLock {
        val def = CompletableDeferred<String>()
        synchronized(lock) {
            buffer.clear()
            pendingResponse = def
        }
        writeRaw((cmd + "\r").toByteArray(Charsets.ISO_8859_1))
        try {
            withTimeout(timeoutMs) { def.await() }
        } catch (e: TimeoutCancellationException) {
            val partial = synchronized(lock) { buffer.toString().also { buffer.clear() } }
            throw ElmException("Timeout på '$cmd'" + if (partial.isNotBlank()) " (delsvar: ${partial.trim()})" else "")
        } finally {
            pendingResponse = null
        }
    }

    fun close() {
        userClosed = true
        try { gatt?.disconnect() } catch (_: Exception) { }
        try { gatt?.close() } catch (_: Exception) { }
        gatt = null
        rxChar = null
        txChar = null
    }
}
