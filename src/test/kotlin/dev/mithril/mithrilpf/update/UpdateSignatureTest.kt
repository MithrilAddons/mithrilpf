package dev.mithril.mithrilpf.update

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.*
import org.junit.Test

class UpdateSignatureTest {
    private val key = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val release =
        UpdateRelease(
            ReleaseVersion.parse("1.0.0")!!,
            URI("${UpdateCatalog.REPO}/releases/download/v1.0.0/mithrilpf-1.0.0.jar"),
            1024,
            "a".repeat(64),
        )

    private fun sign(payload: ByteArray): ByteArray =
        Signature.getInstance("Ed25519").run {
            initSign(key.private)
            update(payload)
            sign()
        }

    @Test
    fun acceptsValidSignatureAndPackagedTrustAnchor() {
        UpdateSignature.verify(release, sign(UpdateSignature.payload(release)), key.public.encoded)
        val pinned = UpdateSignature.trustedKey()
        assertEquals(44, pinned.size)
        assertFails {
            UpdateSignature.verify(release, sign(UpdateSignature.payload(release)), pinned)
        }
    }

    @Test
    fun rejectsTamperingWrongKeysAndMalformedSignatures() {
        val signature = sign(UpdateSignature.payload(release))
        for (changed in
            listOf(
                release.copy(size = 1025),
                release.copy(sha256 = "b".repeat(64)),
                release.copy(version = ReleaseVersion.parse("1.0.1")!!),
            )) {
            assertFails { UpdateSignature.verify(changed, signature, key.public.encoded) }
        }
        val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        assertFails { UpdateSignature.verify(release, signature, other.public.encoded) }
        assertFails { UpdateSignature.verify(release, signature, byteArrayOf(1, 2)) }
        for (bad in
            listOf(
                byteArrayOf(),
                signature.copyOf(63),
                signature.copyOf(65),
                ByteArray(64),
                signature.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() },
            )) {
            assertFails { UpdateSignature.verify(release, bad, key.public.encoded) }
        }
        val otherRepository =
            UpdateSignature.payload(release)
                .toString(Charsets.US_ASCII)
                .replace("MithrilAddons/mithrilpf", "attacker/another-mod")
                .toByteArray()
        assertFails { UpdateSignature.verify(release, sign(otherRepository), key.public.encoded) }
        val crlf =
            UpdateSignature.payload(release)
                .toString(Charsets.US_ASCII)
                .replace("\n", "\r\n")
                .toByteArray()
        assertFails { UpdateSignature.verify(release, sign(crlf), key.public.encoded) }
    }

    @Test
    fun verifiesActualReleaseToolOutput() {
        val dir = Files.createTempDirectory("release-signing-test")
        try {
            val publicText = Base64.getEncoder().encodeToString(key.public.encoded) + "\n"
            val pub = Files.writeString(dir.resolve("test.pub"), publicText)
            val jar = dir.resolve("mithrilpf-1.0.0.jar")
            ZipOutputStream(Files.newOutputStream(jar)).use { zip ->
                for ((name, value) in
                    mapOf(
                        "assets/mithrilpf/release-signing.pub" to publicText,
                        "assets/mithrilpf/build.properties" to
                            "version=1.0.0\nofficialRelease=true\n",
                    )) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(value.toByteArray())
                    zip.closeEntry()
                }
            }
            val signature = dir.resolve("release.sig")
            val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
            val process =
                ProcessBuilder(
                        java,
                        "tools/ReleaseSigning.java",
                        "sign",
                        "1.0.0",
                        jar.toString(),
                        signature.toString(),
                        pub.toString(),
                    )
                    .redirectErrorStream(true)
            process.environment()["RELEASE_SIGNING_KEY"] =
                Base64.getEncoder().encodeToString(key.private.encoded)
            val child = process.start()
            try {
                assertTrue(child.waitFor(30, TimeUnit.SECONDS), "Release signer timed out")
                val output = child.inputStream.bufferedReader().use { it.readText() }
                assertEquals(0, child.exitValue(), output)
            } finally {
                child.destroyForcibly()
            }
            val artifact = release.copy(size = Files.size(jar), sha256 = UpdateArtifact.hash(jar))
            UpdateSignature.verify(artifact, Files.readAllBytes(signature), key.public.encoded)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
