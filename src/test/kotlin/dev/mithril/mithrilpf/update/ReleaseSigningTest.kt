package dev.mithril.mithrilpf.update

import dev.mithril.mithrilpf.release.ReleaseSigning
import java.io.RandomAccessFile
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*
import org.junit.Test

class ReleaseSigningTest {
    private class Fixture : AutoCloseable {
        val root = Files.createTempDirectory("release-tool-test")
        val privateFile = root.resolve("test.key")
        val publicFile = root.resolve("test.pub")
        val signature = root.resolve("release.sig")
        var version = "1.0.0"
        val jar: Path
            get() = root.resolve("mithrilpf-$version.jar")

        val secret: String
            get() = Files.readString(privateFile).trim()

        init {
            ReleaseSigning.run(
                arrayOf("generate", privateFile.toString(), publicFile.toString()),
                null,
            )
            writeJar()
        }

        fun writeJar(
            publicText: String = Files.readString(publicFile),
            marker: String = "version=$version\nofficialRelease=true\n",
        ) {
            ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
                for ((name, value) in
                    mapOf(
                        "assets/mithrilpf/release-signing.pub" to publicText,
                        "assets/mithrilpf/build.properties" to marker,
                    )) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(value.toByteArray())
                    zip.closeEntry()
                }
            }
        }

        fun run(
            command: String = "sign",
            key: String? = secret,
            requestedVersion: String = version,
            artifact: Path = jar,
            pub: Path = publicFile,
        ) =
            ReleaseSigning.run(
                arrayOf(
                    command,
                    requestedVersion,
                    artifact.toString(),
                    signature.toString(),
                    pub.toString(),
                ),
                key,
            )

        override fun close() {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun signsAndVerifiesStableAndPrereleaseArtifactsWithoutChangingJar() {
        for (version in listOf("1.0.0", "0.2.0-rc.14", "2.0.0-alpha.1", "2.0.0-beta.2")) Fixture()
            .use { f ->
                f.version = version
                f.writeJar()
                val before = Files.readAllBytes(f.jar)
                f.run()
                f.run("verify", null)
                assertContentEquals(before, Files.readAllBytes(f.jar))
                val release =
                    UpdateRelease(
                        ReleaseVersion.parse(version)!!,
                        URI("https://example.invalid"),
                        Files.size(f.jar),
                        UpdateArtifact.hash(f.jar),
                    )
                UpdateSignature.verify(
                    release,
                    Files.readAllBytes(f.signature),
                    Base64.getDecoder().decode(Files.readString(f.publicFile).trim()),
                )
                assertFails { f.run() }
            }
    }

    @Test
    fun neverOverwritesKeysOrAcceptsInvalidArguments() =
        Fixture().use { f ->
            val secret = f.secret
            assertFails {
                ReleaseSigning.run(
                    arrayOf("generate", f.privateFile.toString(), f.publicFile.toString()),
                    null,
                )
            }
            assertFails {
                ReleaseSigning.run(
                    arrayOf(
                        "generate",
                        f.root.resolve("new.key").toString(),
                        f.publicFile.toString(),
                    ),
                    null,
                )
            }
            assertEquals(secret, f.secret)
            assertFalse(Files.exists(f.root.resolve("new.key")))
            for (args in
                listOf(emptyArray(), arrayOf("unknown"), arrayOf("unknown", "1", "2", "3", "4"))) {
                assertFails { ReleaseSigning.run(args, null) }
            }
        }

    @Test
    fun rejectsMissingWrongOrMalformedKeys() =
        Fixture().use { f ->
            Fixture().use { other ->
                for (secret in listOf(null, "invalid", "x".repeat(257), other.secret)) {
                    assertFails { f.run(key = secret) }
                    assertFalse(Files.exists(f.signature))
                }
                for (publicText in listOf("x".repeat(129), "invalid", "AQID")) {
                    Files.writeString(f.publicFile, publicText)
                    assertFails { f.run() }
                }
            }
        }

    @Test
    fun rejectsNoncanonicalOrMismatchedVersions(): Unit =
        Fixture().use { f ->
            for (version in
                listOf(
                    "01.0.0",
                    "1.0",
                    "1.0.0-rc.0",
                    "1.0.0-rc.01",
                    "1.0.0\n",
                    "1.0.1",
                    "2147483648.0.0",
                    "1".repeat(65),
                )) {
                assertFails { f.run(requestedVersion = version) }
            }
            val otherName = Files.copy(f.jar, f.root.resolve("other.jar"))
            assertFails { f.run(artifact = otherName) }
        }

    @Test
    fun rejectsWrongPackagedTrustAnchorAndDistribution(): Unit =
        Fixture().use { f ->
            Fixture().use { other ->
                for (publicText in
                    listOf(Files.readString(other.publicFile), "x".repeat(129), "invalid")) {
                    f.writeJar(publicText = publicText)
                    assertFails { f.run() }
                }
                for (marker in
                    listOf(
                        "version=1.0.0\nofficialRelease=false\n",
                        "version=1.0.1\nofficialRelease=true\n",
                        "x".repeat(1025),
                    )) {
                    f.writeJar(marker = marker)
                    assertFails { f.run() }
                }
                ZipOutputStream(Files.newOutputStream(f.jar)).use {}
                assertFails { f.run() }
            }
        }

    @Test
    fun rejectsChangedArtifactsAndMalformedSignatures(): Unit =
        Fixture().use { f ->
            f.run()
            val signature = Files.readAllBytes(f.signature)
            for (bad in
                listOf(byteArrayOf(), signature.copyOf(63), signature.copyOf(65), ByteArray(64))) {
                Files.write(f.signature, bad)
                assertFails { f.run("verify", null) }
            }
            Files.write(f.signature, signature)
            f.writeJar(marker = "version=1.0.0\nofficialRelease=true\n# changed\n")
            assertFails { f.run("verify", null) }
            Files.write(f.jar, byteArrayOf())
            assertFails { f.run() }
            RandomAccessFile(f.jar.toFile(), "rw").use {
                it.setLength(UpdateCatalog.MAX_JAR.toLong() + 1)
            }
            assertFails { f.run() }
        }
}
