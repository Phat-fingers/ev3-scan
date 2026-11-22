package se.ev3scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Testvektorer: riktiga EV3-svar från OBDb/Kia-EV3 (CC BY-SA 4.0). */
class Ev3DecoderTest {

    private val main2025 = """
        7EC103E620101EFFBE7
        7EC21EF120000000000
        7EC2200000C1C222122
        7EC23222222220045A7
        7EC2426A60100007E00
        7EC2501DD200001DD43
        7EC26000069DE000066
        7EC27C900B717AA0000
        7EC2800000000000BB8
    """.trimIndent()

    @Test
    fun decodesMain() {
        val d = ElmParser.udsPayload(main2025, 0x0101, "7EC")
        val m = Ev3Decoder.decodeMain(d)
        assertEquals(9.0, m.socBms!!, 0.001)
        assertEquals(310.0, m.voltageV!!, 0.001)
        assertEquals(0.0, m.currentA!!, 0.001)
        assertEquals(34, m.tempMax)
        assertEquals(33, m.tempMin)
        assertEquals(29, m.inletTemp)
        assertEquals(3.34, m.cellMaxV!!, 0.001)
        assertEquals(38, m.cellMaxNo)
        assertEquals(3.32, m.cellMinV!!, 0.001)
        assertEquals(1, m.cellMinNo)
        assertEquals(12.6, m.auxV!!, 0.001)
        assertEquals(false, m.charging)
        assertEquals(listOf(34, 34, 34, 34, 34), m.moduleTemps)
    }

    @Test
    fun decodesHealth() {
        val raw = """
            7EC102E6201051FFB74
            7EC210F141401021406
            7EC2206060605050540
            7EC235B4268000050FE
            7EC240003E8064FF800
            7EC256F000000000000
            7EC260005050606AAAA
        """.trimIndent()
        val h = Ev3Decoder.decodeHealth(ElmParser.udsPayload(raw, 0x0105, "7EC"))
        assertEquals(100.0, h.sohPct!!, 0.001)
        assertEquals(55.5, h.socDisplay!!, 0.001)
        assertEquals(40.944, h.remainingKwh!!, 0.001)
    }

    @Test
    fun parsesNoHeaderFormat() {
        val raw = "03E\r0:620101EFFBE7\r1:EF120000000000\r2:00000C1C222122\r\r>"
        val d = ElmParser.udsPayload(raw, 0x0101)
        assertEquals(0x12, d[4].toInt() and 0xFF)
    }

    @Test
    fun spacesAndPromptAreIgnored() {
        val raw = "7EC 10 3E 62 01 01 EF FB E7\r7EC 21 EF 12 00 00 00 00 00\r\r>"
        val d = ElmParser.udsPayload(raw, 0x0101, "7EC")
        assertEquals(0x12, d[4].toInt() and 0xFF)
    }

    @Test(expected = ElmException::class)
    fun noDataThrows() {
        ElmParser.udsPayload("NO DATA\r\r>", 0x0101)
    }

    @Test
    fun cellsDecode() {
        val cells = Ev3Decoder.decodeCells(ByteArray(40) { if (it >= 4) 0xAE.toByte() else 0 })
        assertEquals(32, cells.size)
        assertTrue(cells.all { kotlin.math.abs(it - 3.48) < 0.001 })
    }

    @Test
    fun decodesVcmsE001() {
        val raw = """
            74C102762E001FFFFFF
            74C21C0000000010000
            74C220004F705032804
            74C2300910F70010E00
            74C2400002500000000
            74C257E01001320AAAA
        """.trimIndent()
        val c = VcmsDecoder.applyE001(ChargerInfo(), ElmParser.udsPayload(raw, 0xE001, "74C"))
        assertEquals(395.2, c.obcDcVoltage!!, 0.001)
        assertEquals(27.0, c.obcDcCurrent!!, 0.001)
        assertEquals(-50, c.estimatedPowerW)
        assertEquals(false, c.acCharging)
        assertEquals(false, c.dcCharging)
    }
}
