package jp.co.primehub.pos

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.Charset

/**
 * Prime POS ticket text -> ESC/POS bytes for 80mm / 58mm network printers
 * (Epson TM-T20II / TM-T88V, SII RP-F10, Star in ESC/POS mode ...).
 *
 * Ticket lines may start with markers (any order, any number):
 *   [[CENTER]]  centre this line
 *   [[BIG]]     double width + double height
 *   [[BOLD]]    bold
 *   [[DRAWER]]  open the cash drawer (a line with only this prints nothing)
 * Japanese text is sent as Shift-JIS (Windows-31J) with Kanji mode on, which Japanese-model printers expect.
 */
object EscPos {
    private const val ESC: Byte = 0x1B
    private const val GS: Byte = 0x1D
    private const val FS: Byte = 0x1C
    private val SJIS: Charset = Charset.forName("windows-31j")
    private val MARK = Regex("^\\[\\[(CENTER|BIG|BOLD|DRAWER)]]")

    fun render(text: String, kanji: Boolean = true, cut: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(ESC, 0x40))                                        // initialise
        if (kanji) out.write(byteArrayOf(FS, 0x26, FS, 0x43, 0x01))              // Kanji mode on, Shift-JIS
        for (raw in text.replace("\r\n", "\n").trimEnd('\n').split("\n")) {
            var line = raw
            var center = false; var big = false; var bold = false; var drawer = false
            while (true) {
                val m = MARK.find(line) ?: break
                when (m.groupValues[1]) { "CENTER" -> center = true; "BIG" -> big = true; "BOLD" -> bold = true; "DRAWER" -> drawer = true }
                line = line.substring(m.value.length)
            }
            if (drawer) out.write(drawerKick())
            if (drawer && line.isEmpty()) continue
            if (center) out.write(byteArrayOf(ESC, 0x61, 0x01))
            if (big) out.write(byteArrayOf(GS, 0x21, 0x11))
            if (bold) out.write(byteArrayOf(ESC, 0x45, 0x01))
            out.write(encode(line, kanji))
            out.write(0x0A)
            if (center) out.write(byteArrayOf(ESC, 0x61, 0x00))
            if (big) out.write(byteArrayOf(GS, 0x21, 0x00))
            if (bold) out.write(byteArrayOf(ESC, 0x45, 0x00))
        }
        if (cut) out.write(byteArrayOf(ESC, 0x64, 0x04, GS, 0x56, 0x42, 0x00))    // feed 4 lines, partial cut
        return out.toByteArray()
    }

    /** ESC p 0 25 250: pulse on drawer pin 2 (the usual drawer connector) */
    fun drawerKick(): ByteArray = byteArrayOf(ESC, 0x70, 0x00, 0x19, 0xFA.toByte())

    private fun encode(s: String, kanji: Boolean): ByteArray {
        // Japanese printers print 0x5C as ¥ ; Windows-31J has no ¥ of its own
        val t = s.replace('¥', '\\').replace('￥', '\\')
        return if (kanji) t.toByteArray(SJIS) else t.toByteArray(Charsets.US_ASCII)
    }

    /** send bytes to a network printer (raw port 9100). Throws on any network error. */
    fun send(ip: String, port: Int, data: ByteArray, copies: Int = 1, timeoutMs: Int = 5000) {
        Socket().use { s ->
            s.connect(InetSocketAddress(ip, port), timeoutMs)
            s.soTimeout = timeoutMs
            val o = s.getOutputStream()
            repeat(copies.coerceIn(1, 5)) { o.write(data) }
            o.flush()
        }
    }

    fun testTicket(name: String, width: Int): String {
        val line = "-".repeat(width.coerceIn(24, 64))
        return listOf(
            "[[CENTER]][[BIG]]Prime POS",
            "[[CENTER]]テスト印刷 Test print",
            line,
            "プリンター: $name",
            "日本語の印字テスト：バターチキン ¥1,290",
            "English: Butter Chicken x 2",
            line,
            "[[CENTER]][[BOLD]]OK",
        ).joinToString("\n")
    }
}
