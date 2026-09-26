package dev.mithril.mithrilpf.dungeontimer

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json

/** One immutable JSON per accepted run. Worker-owned; separate instances never rewrite a ledger. */
class DungeonRunStore(private val directory: Path) {
    private val json = Json { ignoreUnknownKeys = true }
    private val records = linkedMapOf<String, DungeonRunRecord>()

    fun load(onInvalid: (Path, Exception) -> Unit = { _, _ -> }) {
        Files.createDirectories(directory)
        records.clear()
        Files.newDirectoryStream(directory, "*.json").use { files ->
            files.forEach { file ->
                try {
                    val bytes = Files.newInputStream(file).use { it.readNBytes(65_537) }
                    require(bytes.size <= 65_536) { "Run record exceeds size limit" }
                    val record =
                        json.decodeFromString<DungeonRunRecord>(bytes.toString(Charsets.UTF_8))
                    record.validate()
                    require(file.fileName.toString() == "${record.id}.json")
                    records[record.id] = record
                } catch (e: Exception) {
                    onInvalid(file, e)
                }
            }
        }
    }

    fun append(record: DungeonRunRecord): Boolean {
        record.validate()
        if (records[record.id] == record) return false
        val target = directory.resolve("${record.id}.json")
        check(!Files.exists(target)) { "Refusing to replace an existing run" }
        val text = json.encodeToString(record)
        require(text.toByteArray(Charsets.UTF_8).size <= 65_536)
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, "run-", ".tmp")
        try {
            Files.writeString(temporary, text)
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary, target)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
        records[record.id] = record
        return true
    }

    fun summaries(): Map<String, Map<String, DungeonRunStatistics>> =
        records.values
            .groupBy { it.player }
            .mapValues { (_, runs) ->
                runs
                    .groupBy { it.floor }
                    .mapValues { (_, floorRuns) ->
                        requireNotNull(DungeonRunStatistics.from(floorRuns))
                    }
            }
}
