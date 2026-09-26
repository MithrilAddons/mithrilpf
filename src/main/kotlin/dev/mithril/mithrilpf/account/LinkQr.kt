package dev.mithril.mithrilpf.account

import io.nayuki.qrcodegen.QrCode
import java.net.URI

/** Computed once on the link worker. Rendering only visits cached horizontal runs. */
class LinkQr(uri: URI) {
    data class Run(val x: Int, val y: Int, val width: Int)

    val size: Int
    val runs: List<Run>

    init {
        require(uri.toString().matches(Regex("https://mithril\\.foo/link#[A-Za-z0-9_-]{43}")))
        val qr = QrCode.encodeText(uri.toString(), QrCode.Ecc.MEDIUM)
        size = qr.size + 8 // Four-module quiet zone, always opaque white.
        runs = buildList {
            for (y in 0 until qr.size) {
                var x = 0
                while (x < qr.size) {
                    if (!qr.getModule(x, y)) {
                        x++
                        continue
                    }
                    val start = x
                    while (x < qr.size && qr.getModule(x, y)) x++
                    add(Run(start + 4, y + 4, x - start))
                }
            }
        }
    }
}
