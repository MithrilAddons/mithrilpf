package dev.mithril.mithrilpf.account

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Instance-local native credentials; all access runs on a worker, never a render/tick callback. */
class DeviceSessionStore(private val path: Path) {
    fun load(uuid: String): DeviceSession? {
        val row = read().getAsJsonObject("accounts")?.getAsJsonObject(uuid) ?: return null
        return decode(row).takeIf { it.expires > System.currentTimeMillis() / 1000 }
    }

    fun save(uuid: String, session: DeviceSession?) {
        require(uuid.matches(Regex("[0-9a-f]{32}")))
        val root = read()
        val accounts =
            root.getAsJsonObject("accounts") ?: JsonObject().also { root.add("accounts", it) }
        if (session == null) accounts.remove(uuid)
        else {
            val row = accounts.getAsJsonObject(uuid) ?: JsonObject()
            row.addProperty("token", session.token)
            row.addProperty("receipt", session.receipt)
            row.addProperty("expires", session.expires)
            decode(row)
            accounts.add(uuid, row)
        }
        val bytes = root.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 65536)
        Files.createDirectories(path.parent)
        val temp = Files.createTempFile(path.parent, "device-", ".tmp")
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
            for ((uuid, row) in root.getAsJsonObject("accounts").entrySet()) {
                require(uuid.matches(Regex("[0-9a-f]{32}")))
                decode(row.asJsonObject)
            }
        }
        return root
    }

    private fun decode(row: JsonObject): DeviceSession {
        fun credential(key: String): String {
            val value = row.get(key)
            require(value?.isJsonPrimitive == true && value.asJsonPrimitive.isString)
            return value.asString.also { require(it.matches(Regex("[A-Za-z0-9_-]{43}"))) }
        }
        val expires = row.get("expires")
        require(expires?.isJsonPrimitive == true && expires.asJsonPrimitive.isNumber)
        require(expires.toString().matches(Regex("[0-9]{1,12}")))
        return DeviceSession(credential("token"), credential("receipt"), expires.asLong)
    }
}
