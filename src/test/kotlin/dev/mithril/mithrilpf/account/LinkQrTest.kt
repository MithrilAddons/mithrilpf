package dev.mithril.mithrilpf.account

import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.Decoder
import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class LinkQrTest {
    @Test
    fun `independent decoder reads exact login URI with quiet zone intact`() {
        val uri = URI("https://mithril.foo/link#${"Ab_9-".repeat(8)}xyz")
        val qr = LinkQr(uri)
        val matrix = BitMatrix(qr.size - 8)
        for (run in qr.runs) {
            assertTrue(run.x >= 4 && run.y >= 4)
            assertTrue(run.x + run.width <= qr.size - 4 && run.y < qr.size - 4)
            for (x in run.x until run.x + run.width) matrix.set(x - 4, run.y - 4)
        }
        assertEquals(uri.toString(), Decoder().decode(matrix).text)
        assertTrue(qr.size <= 57)
    }

    @Test
    fun `rejects non login destinations`() {
        for (uri in
            listOf(
                "https://evil.invalid/link#${"a".repeat(43)}",
                "https://mithril.foo/link#bad",
            )) assertFails { LinkQr(URI(uri)) }
    }
}
