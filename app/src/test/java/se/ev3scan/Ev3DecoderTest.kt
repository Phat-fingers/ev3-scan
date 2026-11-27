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
        assertEquals(false, c.acCharging)
        assertEquals(false, c.dcCharging)
    }

    /** Riktig EV3 under DC-snabbladdning (användarens bil, 2026-11-27). */
    @Test
    fun decodesRealDcCharging() {
        val main = """
            7EC103E620101EFFBE7
            7EC21EF340F70067E00
            7EC22F9D00D980A0405
            7EC23040404040029BB
            7EC2441BA2100009200
            7EC25009FAC00009B4F
            7EC2600003995000035
            7EC27F5004E49550001
            7EC285C000000000BB8
        """.trimIndent()
        val m = Ev3Decoder.decodeMain(ElmParser.udsPayload(main, 0x0101, "7EC"))
        assertEquals(26.0, m.socBms!!, 0.001)
        assertEquals(-158.4, m.currentA!!, 0.001)
        assertEquals(348.0, m.voltageV!!, 0.001)
        assertEquals(395.2, m.targetVoltageV!!, 0.001)
        assertEquals(166.2, m.targetCurrentA!!, 0.001)
        assertTrue(m.powerKw!! < -50)

        val e001 = """
            74C102762E001FFFFFF
            74C21C046CA00640000
            74C220005D005D61F00
            74C2308910F70000000
            74C2400002500264928
            74C250301001320AAAA
        """.trimIndent()
        val e002 = """
            74C102562E002FFFFF8
            74C21003501741A0013
            74C228809B005DC0000
            74C230D84073A040000
            74C240060E04000000D
            74C25911200AAAAAAAA
        """.trimIndent()
        val e003 = """
            74C103F62E003FFFFFF
            74C21FE53053A003427
            74C22100E880D982E0F
            74C237007430F700DAC
            74C240F70067E000000
            74C2530001C00E60008
            74C26000009B0000000
            74C270808BB029810BD
            74C28000207F6055900
            74C2900AAAAAAAAAAAA
        """.trimIndent()
        val e004 = """
            74C101A62E004FFFFC0
            74C2100050F25023636
            74C2233515053490800
            74C230004CE000000AA
        """.trimIndent()
        var c = ChargerInfo()
        c = VcmsDecoder.applyE001(c, ElmParser.udsPayload(e001, 0xE001, "74C"))
        c = VcmsDecoder.applyE002(c, ElmParser.udsPayload(e002, 0xE002, "74C"))
        c = VcmsDecoder.applyE003(c, ElmParser.udsPayload(e003, 0xE003, "74C"))
        c = VcmsDecoder.applyE004(c, ElmParser.udsPayload(e004, 0xE004, "74C"))
        assertEquals(true, c.dcCharging)
        assertEquals(false, c.acCharging)
        assertEquals(true, c.socketConnected)
        assertEquals(5.8, c.cpVoltage!!, 0.001)
        assertEquals(5.2, c.cpDuty!!, 0.001)
        assertEquals(8, c.dcMinutesSincePlug)
        assertEquals(500.0, c.evseMaxVoltage!!, 0.001)
        assertEquals(248.0, c.evseMaxCurrent!!, 0.001)
        assertEquals(346.0, c.evseVoltage!!, 0.001)
    }

    /** Samma bil i vila utan kabel (2026-11-26). */
    @Test
    fun decodesRealIdleUnplugged() {
        val e004 = """
            74C101A62E004FFFFC0
            74C2100040001012F2F
            74C222F555555000000
            74C23000155000000AA
        """.trimIndent()
        val c = VcmsDecoder.applyE004(ChargerInfo(), ElmParser.udsPayload(e004, 0xE004, "74C"))
        assertEquals(false, c.socketConnected)
        assertEquals(true, c.mainRelay)
    }
}
