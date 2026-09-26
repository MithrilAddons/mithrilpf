package dev.mithril.mithrilpf.update

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.junit.Test

class UpdateProcessTest {
    @Test
    fun helperWaitsForParentAndInstallsOnlyAfterExit() {
        val dir = Files.createTempDirectory("updater-process-test").toRealPath()
        val java =
            Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
                )
                .toString()
        val fixtureClasses =
            Path.of(WaitingProcess::class.java.protectionDomain.codeSource.location.toURI())
                .toString()
        val parent =
            ProcessBuilder(java, "-cp", fixtureClasses, WaitingProcess::class.java.name).start()
        var helper: Process? = null
        try {
            assertEquals("ready", parent.inputStream.bufferedReader().readLine())
            val old =
                Files.writeString(
                    Files.createDirectory(dir.resolve("mods")).resolve("mithrilpf.jar"),
                    "original",
                )
            val staged =
                Files.writeString(
                    Files.createDirectories(dir.resolve("config/mithrilpf/updates/job-test"))
                        .resolve("update.jar"),
                    "replacement",
                )
            helper =
                ProcessBuilder(
                        java,
                        "-jar",
                        Path.of("build/updater/updater.jar").toAbsolutePath().toString(),
                        parent.pid().toString(),
                        parent.info().startInstant().orElseThrow().toEpochMilli().toString(),
                        old.toString(),
                        staged.toString(),
                        UpdateArtifact.hash(old),
                        UpdateArtifact.hash(staged),
                    )
                    .redirectErrorStream(true)
                    .redirectOutput(dir.resolve("helper.log").toFile())
                    .start()
            assertFalse(helper.waitFor(250, TimeUnit.MILLISECONDS))
            assertEquals("original", Files.readString(old))
            parent.outputStream.close()
            assertTrue(parent.waitFor(5, TimeUnit.SECONDS))
            assertTrue(helper.waitFor(10, TimeUnit.SECONDS))
            assertEquals(0, helper.exitValue(), Files.readString(dir.resolve("helper.log")))
            assertEquals("replacement", Files.readString(old))
        } finally {
            parent.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            helper?.destroyForcibly()?.waitFor(5, TimeUnit.SECONDS)
            dir.toFile().deleteRecursively()
        }
    }

    @Test
    fun waitHasRealTimeout() {
        val java =
            Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java",
                )
                .toString()
        val fixtureClasses =
            Path.of(WaitingProcess::class.java.protectionDomain.codeSource.location.toURI())
                .toString()
        val parent =
            ProcessBuilder(java, "-cp", fixtureClasses, WaitingProcess::class.java.name).start()
        try {
            assertEquals("ready", parent.inputStream.bufferedReader().readLine())
            assertFailsWith<java.util.concurrent.TimeoutException> {
                UpdateInstaller.waitForExit(
                    parent.pid(),
                    parent.info().startInstant().orElseThrow().toEpochMilli(),
                    1,
                )
            }
            assertTrue(parent.isAlive)
        } finally {
            parent.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
        }
    }
}
