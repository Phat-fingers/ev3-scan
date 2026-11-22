package se.ev3scan

class ElmException(message: String) : Exception(message)

/**
 * Tolkar textsvar från en ELM327-adapter och sätter ihop ISO-TP-ramar (CAN, 11-bit)
 * till en enda byte-array.
 *
 * Stöder båda vanliga format:
 *  - Med headers (ATH1):   "7EC103E620101EFFBE7" / "7EC21EF120000000000" ...
 *  - Utan headers (ATH0):  "03E" / "0:620101EFFBE7" / "1:EF120000000000" ...
 */
object ElmParser {

    private val ERRORS = listOf(
        "NODATA", "CANERROR", "BUSERROR", "BUSINIT", "STOPPED", "UNABLETOCONNECT",
        "BUFFERFULL", "FBERROR", "DATAERROR", "ERR", "?"
    )

    fun cleanLines(raw: String): List<String> =
        raw.replace("\r", "\n")
            .split("\n")
            .map { it.replace(" ", "").replace(">", "").trim().uppercase() }
            .filter { it.isNotEmpty() && it != "SEARCHING..." && it != "OK" }

    /** Returnerar hela ISO-TP-nyttolasten (t.ex. 62 01 01 ...). */
    fun assemble(raw: String, rxHeader: String? = null): ByteArray {
        val lines = cleanLines(raw)
        if (lines.isEmpty()) throw ElmException("Tomt svar")
        for (l in lines) {
            val compact = l.replace(".", "")
            if (ERRORS.any { compact.startsWith(it) || compact == it }) throw ElmException(l)
        }

        // Format utan headers: "03E", "0:....", "1:...."
        if (lines.any { it.contains(':') }) {
            var total = -1
            val out = ArrayList<Byte>()
            for (l in lines) {
                if (l.contains(':')) {
                    out.addAll(hexToBytes(l.substringAfter(':')).toList())
                } else if (l.length == 3 && isHex(l)) {
                    total = l.toInt(16)
                }
            }
            val arr = out.toByteArray()
            return if (total in 1..arr.size) arr.copyOf(total) else arr
        }

        val frames = ArrayList<ByteArray>()
        for (l in lines) {
            if (!isHex(l)) continue
            val data: String = if (l.length % 2 == 1) {
                val hdr = l.substring(0, 3)
                if (rxHeader != null && !hdr.equals(rxHeader, ignoreCase = true)) continue
                l.substring(3)
            } else l
            if (data.length >= 2) frames.add(hexToBytes(data))
        }
        if (frames.isEmpty()) throw ElmException("Inga ramar i svaret")

        val out = ArrayList<Byte>()
        var expected = -1
        for (f in frames) {
            val pci = (f[0].toInt() and 0xF0) shr 4
            when (pci) {
                0 -> { // Single frame
                    val len = f[0].toInt() and 0x0F
                    for (i in 1..minOf(len, f.size - 1)) out.add(f[i])
                    expected = len
                }
                1 -> { // First frame
                    expected = ((f[0].toInt() and 0x0F) shl 8) or (f[1].toInt() and 0xFF)
                    for (i in 2 until f.size) out.add(f[i])
                }
                2 -> { // Consecutive frame
                    for (i in 1 until f.size) out.add(f[i])
                }
                3 -> { /* Flow control – ignorera */ }
                else -> {
                    // Okänd ram: troligen redan "rå" data (CAF av) – ta allt
                    out.addAll(f.toList())
                }
            }
        }
        val arr = out.toByteArray()
        return if (expected in 1..arr.size) arr.copyOf(expected) else arr
    }

    /**
     * Läser en UDS-DID (tjänst 0x22) och returnerar data EFTER "62 DID DID",
     * dvs. index 0 = Torque-bokstaven "a".
     */
    fun udsPayload(raw: String, did: Int, rxHeader: String? = null): ByteArray {
        val p = assemble(raw, rxHeader)
        if (p.isNotEmpty() && (p[0].toInt() and 0xFF) == 0x7F) {
            val nrc = if (p.size >= 3) "%02X".format(p[2].toInt() and 0xFF) else "?"
            throw ElmException("Negativt svar (NRC $nrc)")
        }
        if (p.size < 3 || (p[0].toInt() and 0xFF) != 0x62) {
            throw ElmException("Oväntat svar: ${p.toHex()}")
        }
        val gotDid = ((p[1].toInt() and 0xFF) shl 8) or (p[2].toInt() and 0xFF)
        if (gotDid != did) throw ElmException("Fel DID: %04X".format(gotDid))
        return p.copyOfRange(3, p.size)
    }

    private fun isHex(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' || it in 'A'..'F' }

    fun hexToBytes(s: String): ByteArray {
        val clean = s.filter { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }
        val n = clean.length / 2
        return ByteArray(n) { i -> clean.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }
}

fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
