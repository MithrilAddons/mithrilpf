package dev.mithril.mithrilpf.update

import java.nio.charset.StandardCharsets.US_ASCII
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Detached Ed25519 signature; the trust anchor comes only from the installed mod. */
internal object UpdateSignature {
    const val SIZE = 64

    fun trustedKey(): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/assets/mithrilpf/release-signing.pub")).use {
            val bytes = it.readNBytes(129)
            require(bytes.size <= 128)
            Base64.getDecoder().decode(bytes.toString(US_ASCII).trim())
        }

    fun payload(release: UpdateRelease): ByteArray =
        ("MithrilPF release signature v1\n" +
                "repository=MithrilAddons/mithrilpf\n" +
                "version=${release.version.text}\n" +
                "artifact=mithrilpf-${release.version.text}.jar\n" +
                "size=${release.size}\n" +
                "sha256=${release.sha256}\n")
            .toByteArray(US_ASCII)

    fun verify(release: UpdateRelease, signature: ByteArray, key: ByteArray) {
        require(signature.size == SIZE) { "Invalid release signature size" }
        val publicKey = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(key))
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey)
        verifier.update(payload(release))
        require(verifier.verify(signature)) { "Invalid release signature" }
    }
}
