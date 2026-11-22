package se.ev3scan

/**
 * Avkodning av Kia EV3:s batteristyrenhet (BMS, CAN 7E4 -> 7EC).
 *
 * Byte-positioner kommer från OBDb:s signalset för Kia EV3 (github.com/OBDb/Kia-EV3,
 * CC BY-SA 4.0) och är kontrollerade mot deras inspelade svar från riktiga bilar.
 * Index 0 = första byten efter "62 01 0x".
 */
data class BatteryMain(
    val socBms: Double?,
    val availChargeKw: Double?,
    val availDischargeKw: Double?,
    val charging: Boolean?,
    val rapidPlug: Boolean?,
    val normalPlug: Boolean?,
    val mainRelay: Boolean?,
    val currentA: Double?,        // + = urladdning, - = laddning/regen
    val voltageV: Double?,
    val tempMax: Int?,
    val tempMin: Int?,
    val moduleTemps: List<Int>,
    val inletTemp: Int?,
    val cellMaxV: Double?,
    val cellMaxNo: Int?,
    val cellMinV: Double?,
    val cellMinNo: Int?,
    val fanStatus: Int?,
    val fanHz: Int?,
    val auxV: Double?,
    val cumChargeAh: Double?,
    val cumDischargeAh: Double?,
    val cumChargeKwh: Double?,
    val cumDischargeKwh: Double?,
    val operatingHours: Double?,
    val motorRpm: Int?,
    val isolationKohm: Int?,
) {
    /** Effekt i kW. Positiv = batteriet levererar, negativ = laddas/regenererar. */
    val powerKw: Double?
        get() = if (currentA != null && voltageV != null) currentA * voltageV / 1000.0 else null

    val cellDeltaMv: Double?
        get() = if (cellMaxV != null && cellMinV != null) (cellMaxV - cellMinV) * 1000.0 else null
}

data class BatteryHealth(
    val sohPct: Double?,
    val remainingKwh: Double?,
    val socDisplay: Double?,
)

object Ev3Decoder {
    const val BMS_TX = "7E4"
    const val BMS_RX = "7EC"

    const val DID_MAIN = 0x0101
    const val DID_HEALTH = 0x0105
    /** Cellspänningar, 32 celler per DID. */
    val DID_CELLS = listOf(0x0102, 0x0103, 0x0104, 0x010A, 0x010B, 0x010C)

    private fun u8(d: ByteArray, i: Int): Int? = if (i in d.indices) d[i].toInt() and 0xFF else null
    private fun s8(d: ByteArray, i: Int): Int? = if (i in d.indices) d[i].toInt() else null
    private fun u16(d: ByteArray, i: Int): Int? =
        if (i + 1 < d.size && i >= 0) ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF) else null
    private fun s16(d: ByteArray, i: Int): Int? = u16(d, i)?.let { if (it >= 0x8000) it - 0x10000 else it }
    private fun u32(d: ByteArray, i: Int): Long? =
        if (i + 3 < d.size && i >= 0) {
            ((d[i].toLong() and 0xFF) shl 24) or ((d[i + 1].toLong() and 0xFF) shl 16) or
                ((d[i + 2].toLong() and 0xFF) shl 8) or (d[i + 3].toLong() and 0xFF)
        } else null
    private fun bit(d: ByteArray, i: Int, mask: Int): Boolean? = u8(d, i)?.let { (it and mask) != 0 }

    fun decodeMain(d: ByteArray) = BatteryMain(
        socBms = u8(d, 4)?.div(2.0),
        availChargeKw = u16(d, 5)?.div(100.0),
        availDischargeKw = u16(d, 7)?.div(100.0),
        charging = bit(d, 9, 0x80),
        rapidPlug = bit(d, 9, 0x40),
        normalPlug = bit(d, 9, 0x20),
        mainRelay = bit(d, 9, 0x01),
        currentA = s16(d, 10)?.div(10.0),
        voltageV = u16(d, 12)?.div(10.0),
        tempMax = s8(d, 14),
        tempMin = s8(d, 15),
        moduleTemps = (16..20).mapNotNull { s8(d, it) },
        inletTemp = u8(d, 22)?.minus(40),
        cellMaxV = u8(d, 23)?.div(50.0),
        cellMaxNo = u8(d, 24),
        cellMinV = u8(d, 25)?.div(50.0),
        cellMinNo = u8(d, 26),
        fanStatus = u8(d, 27),
        fanHz = u8(d, 28),
        auxV = u8(d, 29)?.div(10.0),
        cumChargeAh = u32(d, 30)?.div(10.0),
        cumDischargeAh = u32(d, 34)?.div(10.0),
        cumChargeKwh = u32(d, 38)?.div(10.0),
        cumDischargeKwh = u32(d, 42)?.div(10.0),
        operatingHours = u32(d, 46)?.div(3600.0),
        motorRpm = s16(d, 53),
        isolationKohm = u16(d, 57),
    )

    fun decodeHealth(d: ByteArray) = BatteryHealth(
        sohPct = u16(d, 25)?.div(10.0),
        remainingKwh = u16(d, 28)?.let { it * 2 / 1000.0 },
        socDisplay = u8(d, 31)?.div(2.0),
    )

    /** 32 cellspänningar (V) per svar. 0 = cell finns inte på denna batteristorlek. */
    fun decodeCells(d: ByteArray): List<Double> =
        (4 until minOf(36, d.size)).map { (d[it].toInt() and 0xFF) / 50.0 }
}

/**
 * Laddstyrenheten VCMS (CAN 744 -> 74C), DID E001/E003/E004.
 * Positioner från OBDb/Kia-EV3, index 0 = första byten efter "62 E0 0x".
 */
data class ChargerInfo(
    val dcCharging: Boolean? = null,
    val acCharging: Boolean? = null,
    val obcDcVoltage: Double? = null,
    val obcDcCurrent: Double? = null,
    val obcAcCurrent: Double? = null,
    val obcAcVoltage: Int? = null,
    val estimatedPowerW: Int? = null,
    val cpVoltage: Double? = null,
    val cpDuty: Double? = null,
    val acMinutesSincePlug: Int? = null,
    val dcMinutesSincePlug: Int? = null,
    val mainRelay: Boolean? = null,
    val socketConnected: Boolean? = null,
    val dcInletT1: Int? = null,
    val dcInletT2: Int? = null,
    val acInletT: Int? = null,
)

object VcmsDecoder {
    const val TX = "744"
    const val RX = "74C"
    const val DID_E001 = 0xE001
    const val DID_E003 = 0xE003
    const val DID_E004 = 0xE004

    private fun u8(d: ByteArray, i: Int): Int? = if (i in d.indices) d[i].toInt() and 0xFF else null
    private fun u16(d: ByteArray, i: Int): Int? =
        if (i >= 0 && i + 1 < d.size) ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF) else null
    private fun bit(d: ByteArray, i: Int, mask: Int): Boolean? = u8(d, i)?.let { (it and mask) != 0 }

    fun applyE001(c: ChargerInfo, d: ByteArray) = c.copy(
        dcCharging = bit(d, 17, 0x08),
        acCharging = bit(d, 17, 0x04),
        obcDcVoltage = u16(d, 19)?.div(10.0),
        obcDcCurrent = u16(d, 21)?.div(10.0),
        obcAcCurrent = u16(d, 23)?.div(10.0),
        obcAcVoltage = u8(d, 25),
        estimatedPowerW = u16(d, 28)?.let { it * 10 - 50 },
    )

    fun applyE003(c: ChargerInfo, d: ByteArray) = c.copy(
        cpVoltage = u8(d, 6)?.div(10.0),
        cpDuty = u16(d, 7)?.div(10.0),
        acMinutesSincePlug = u16(d, 42),
        dcMinutesSincePlug = u16(d, 44),
    )

    fun applyE004(c: ChargerInfo, d: ByteArray) = c.copy(
        mainRelay = bit(d, 4, 0x04),
        socketConnected = bit(d, 4, 0x02),
        dcInletT1 = u8(d, 8)?.minus(50),
        dcInletT2 = u8(d, 9)?.minus(50),
        acInletT = u8(d, 10)?.minus(50),
    )
}
