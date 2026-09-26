package dev.mithril.mithrilpf.dungeontimer

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DungeonBest(val realMillis: Long, val ticks: Long) {
    fun improve(time: SplitTime) =
        DungeonBest(minOf(realMillis, time.realMillis), minOf(ticks, time.ticks))
}

/**
 * Local integrity protection, not anti-cheat: the key is deliberately kept outside the JSON config.
 */
class DungeonPersonalBests(private val directory: Path) {
    private val recordsFile = directory.resolve("dungeon-pbs.dat")
    private val keyFile = directory.resolve("dungeon-pbs.key")
    private var key = byteArrayOf()
    var records: Map<String, Map<String, Map<String, DungeonBest>>> = emptyMap()
        private set

    fun load() {
        Files.createDirectories(directory)
        if (Files.exists(keyFile)) {
            key = Files.readAllBytes(keyFile)
            require(key.size == 32) { "Invalid PB key" }
        } else {
            check(!Files.exists(recordsFile)) {
                "PB key missing; existing records were left untouched"
            }
            key = ByteArray(32).also { SecureRandom().nextBytes(it) }
            Files.write(keyFile, key, java.nio.file.StandardOpenOption.CREATE_NEW)
        }
        if (Files.exists(recordsFile)) records = decode(Files.readString(recordsFile), key)
    }

    /**
     * Saves independent bests, but returns current-run times for splits that improved either PB.
     */
    fun record(
        player: String,
        floor: String,
        completed: Map<String, SplitTime>,
    ): Map<String, SplitTime> {
        val previous = records[player]?.get(floor).orEmpty()
        val improvements =
            completed
                .mapNotNull { (name, time) ->
                    if (time.realMillis <= 0 || time.ticks <= 0) return@mapNotNull null
                    val best =
                        previous[name]?.improve(time) ?: DungeonBest(time.realMillis, time.ticks)
                    if (best == previous[name]) null else name to best
                }
                .toMap()
        if (improvements.isEmpty()) return emptyMap()
        val updated =
            records + (player to (records[player].orEmpty() + (floor to (previous + improvements))))
        val temporary = Files.createTempFile(directory, "dungeon-pbs-", ".tmp")
        try {
            Files.writeString(temporary, encode(updated, key))
            try {
                Files.move(
                    temporary,
                    recordsFile,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, recordsFile, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        records = updated
        return completed.filterKeys { it in improvements }
    }

    companion object {
        private fun signature(payload: String, key: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256")
                .apply { init(SecretKeySpec(key, "HmacSHA256")) }
                .doFinal(payload.toByteArray(Charsets.UTF_8))

        internal fun encode(
            records: Map<String, Map<String, Map<String, DungeonBest>>>,
            key: ByteArray,
        ): String {
            val payload =
                "1." +
                    Base64.getEncoder()
                        .encodeToString(Json.encodeToString(records).toByteArray(Charsets.UTF_8))
            return payload + "." + Base64.getEncoder().encodeToString(signature(payload, key))
        }

        internal fun decode(
            encoded: String,
            key: ByteArray,
        ): Map<String, Map<String, Map<String, DungeonBest>>> {
            val parts = encoded.trim().split('.')
            require(parts.size == 3 && parts[0] == "1") { "Unsupported PB file" }
            require(
                MessageDigest.isEqual(
                    signature(parts.take(2).joinToString("."), key),
                    Base64.getDecoder().decode(parts[2]),
                )
            ) {
                "PB integrity check failed; existing records were left untouched"
            }
            return Json.decodeFromString(
                String(Base64.getDecoder().decode(parts[1]), Charsets.UTF_8)
            )
        }
    }
}
