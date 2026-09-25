package dev.mithril.mithrilpf.account

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Worker-thread only. A receipt can read link status, but cannot authenticate a browser. */
class LinkReceiptStore(private val path: Path) {
    fun load(uuid: String): LinkReceipt? {
        val root = read()
        val accounts = root.getAsJsonObject("accounts") ?: return null
        val record = accounts.getAsJsonObject(uuid) ?: return null
        return decode(record)
    }

    private fun decode(record: JsonObject): LinkReceipt {
        val tokenValue = record.get("token") ?: error("Invalid receipt")
        require(tokenValue.isJsonPrimitive && tokenValue.asJsonPrimitive.isString)
        val token = tokenValue.asString
        require(token.matches(Regex("[A-Za-z0-9_-]{43}")))
        val confirmed = record.get("confirmed")
        require(
            confirmed != null && confirmed.isJsonPrimitive && confirmed.asJsonPrimitive.isBoolean
        )
        return LinkReceipt(token, confirmed.asBoolean)
    }

    fun save(uuid: String, receipt: LinkReceipt?) {
        require(uuid.matches(Regex("[0-9a-f]{32}")))
        val root = read()
        val accounts =
            root.getAsJsonObject("accounts") ?: JsonObject().also { root.add("accounts", it) }
        if (receipt == null) accounts.remove(uuid)
        else {
            require(receipt.token.matches(Regex("[A-Za-z0-9_-]{43}")))
            val record = accounts.getAsJsonObject(uuid) ?: JsonObject()
            record.addProperty("token", receipt.token)
            record.addProperty("confirmed", receipt.confirmed)
            accounts.add(uuid, record)
        }
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 65536)
        Files.createDirectories(path.parent)
        val temp = Files.createTempFile(path.parent, "link-", ".tmp")
        try {
            Files.write(temp, bytes)
            try {
                Files.move(
                    temp,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun read(): JsonObject {
        if (!Files.exists(path)) return JsonObject().apply { addProperty("version", 1) }
        val bytes = Files.newInputStream(path).use { it.readNBytes(65537) }
        require(bytes.size <= 65536)
        val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        require(root.get("version")?.toString() == "1")
        if (root.has("accounts")) {
            require(root.get("accounts").isJsonObject)
            for ((uuid, record) in root.getAsJsonObject("accounts").entrySet()) {
                require(uuid.matches(Regex("[0-9a-f]{32}")))
                decode(record.asJsonObject)
            }
        }
        return root
    }
}

data class LinkReceipt(val token: String, val confirmed: Boolean)
