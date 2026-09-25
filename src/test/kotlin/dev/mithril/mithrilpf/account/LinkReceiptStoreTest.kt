package dev.mithril.mithrilpf.account

import com.google.gson.JsonParser
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull

class LinkReceiptStoreTest {
    private val uuid = "0".repeat(32)
    private val other = "1".repeat(32)
    private val receipt = LinkReceipt("a".repeat(43), true)

    @Test
    fun `saved confirmation survives restart without leaking across accounts`() {
        val directory = Files.createTempDirectory("mithrilpf-receipt")
        try {
            val path = directory.resolve("link.json")
            assertNull(LinkReceiptStore(path).load(uuid))
            LinkReceiptStore(path).save(uuid, receipt)
            assertEquals(receipt, LinkReceiptStore(path).load(uuid))
            assertNull(LinkReceiptStore(path).load(other))
            LinkReceiptStore(path).save(other, receipt.copy(confirmed = false))
            LinkReceiptStore(path).save(uuid, null)
            assertNull(LinkReceiptStore(path).load(uuid))
            assertEquals(receipt.copy(confirmed = false), LinkReceiptStore(path).load(other))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `malformed newer and oversized files are never overwritten`() {
        val directory = Files.createTempDirectory("mithrilpf-receipt")
        try {
            val path = directory.resolve("link.json")
            for (text in
                listOf(
                    "{broken",
                    """{"version":2}""",
                    "x".repeat(65537),
                    """{"version":1,"accounts":[]}""",
                )) {
                Files.writeString(path, text)
                assertFails { LinkReceiptStore(path).load(uuid) }
                assertFails { LinkReceiptStore(path).save(uuid, receipt) }
                assertEquals(text, Files.readString(path))
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `unknown fields survive writes`() {
        val directory = Files.createTempDirectory("mithrilpf-receipt")
        try {
            val path = directory.resolve("link.json")
            Files.writeString(
                path,
                """{"version":1,"extra":7,"accounts":{"$uuid":{"extra":8,"token":"${receipt.token}","confirmed":false}}}""",
            )
            LinkReceiptStore(path).save(uuid, receipt)
            val root = JsonParser.parseString(Files.readString(path)).asJsonObject
            assertEquals(7, root["extra"].asInt)
            assertEquals(8, root.getAsJsonObject("accounts").getAsJsonObject(uuid)["extra"].asInt)
            assertEquals(receipt, LinkReceiptStore(path).load(uuid))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
