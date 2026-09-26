package dev.mithril.mithrilpf.update

import java.io.IOException
import java.nio.file.Files
import kotlin.test.*
import org.junit.Test

class UpdateInstallerTest {
    private fun fixture(
        test: (java.nio.file.Path, java.nio.file.Path, java.nio.file.Path) -> Unit
    ) {
        val root = Files.createTempDirectory("installer-test").toRealPath()
        try {
            val mods = Files.createDirectory(root.resolve("mods"))
            val job = Files.createDirectories(root.resolve("config/mithrilpf/updates/job-test"))
            val old = Files.writeString(mods.resolve("mithrilpf.jar"), "old synthetic jar")
            val staged = Files.writeString(job.resolve("update.jar"), "new synthetic jar")
            test(root, old, staged)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun installsAndKeepsVerifiedBackupWithoutDuplicateMods() = fixture { root, old, staged ->
        val oldHash = UpdateArtifact.hash(old)
        UpdateInstaller.install(old, staged, oldHash, UpdateArtifact.hash(staged))
        assertEquals("new synthetic jar", Files.readString(old))
        assertEquals(
            "old synthetic jar",
            Files.readString(root.resolve("config/mithrilpf/updates/backups/$oldHash.jar.backup")),
        )
        assertFalse(Files.exists(staged))
        Files.list(old.parent).use { assertEquals(1, it.count()) }
    }

    @Test
    fun changedStagingAndManuallyReplacedInstallAreRejected() = fixture { _, old, staged ->
        val oldHash = UpdateArtifact.hash(old)
        val newHash = UpdateArtifact.hash(staged)
        Files.writeString(staged, "tampered")
        assertFails { UpdateInstaller.install(old, staged, oldHash, newHash) }
        assertEquals("old synthetic jar", Files.readString(old))
        Files.writeString(old, "manually updated")
        assertFails { UpdateInstaller.install(old, staged, oldHash, UpdateArtifact.hash(staged)) }
        assertEquals("manually updated", Files.readString(old))
    }

    @Test
    fun failedReplacementKeepsOriginalAndBackup() = fixture { root, old, staged ->
        val hash = UpdateArtifact.hash(old)
        assertFails {
            UpdateInstaller.install(old, staged, hash, UpdateArtifact.hash(staged)) { _, _ ->
                throw IOException("Simulated locked target")
            }
        }
        assertEquals("old synthetic jar", Files.readString(old))
        assertEquals(
            "old synthetic jar",
            Files.readString(root.resolve("config/mithrilpf/updates/backups/$hash.jar.backup")),
        )
        Files.list(old.parent).use { assertEquals(1, it.count()) }
    }

    @Test
    fun pathsOutsideInstanceAreRejected() = fixture { root, old, staged ->
        val outside = Files.writeString(root.resolve("other.jar"), "other")
        assertFails {
            UpdateInstaller.install(
                outside,
                staged,
                UpdateArtifact.hash(outside),
                UpdateArtifact.hash(staged),
            )
        }
        val outsideStage = Files.copy(staged, root.resolve("update.jar"))
        assertFails {
            UpdateInstaller.install(
                old,
                outsideStage,
                UpdateArtifact.hash(old),
                UpdateArtifact.hash(staged),
            )
        }
        assertEquals("other", Files.readString(outside))
        assertEquals("old synthetic jar", Files.readString(old))
    }
}
