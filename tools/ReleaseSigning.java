import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Properties;
import java.util.zip.ZipFile;

/** Release tooling only. Uses the JDK's Ed25519 implementation, with no external dependencies. */
public final class ReleaseSigning {
    private static final String PUBLIC_RESOURCE = "assets/mithrilpf/release-signing.pub";

    public static void main(String[] args) {
        try {
            if (args.length == 3 && args[0].equals("generate")) {
                generate(Path.of(args[1]), Path.of(args[2]));
            } else if (args.length == 5 && (args[0].equals("sign") || args[0].equals("verify"))) {
                process(args);
            } else {
                throw new IllegalArgumentException("Invalid arguments");
            }
        } catch (Exception e) {
            // Never print key material, environment variables, or parser diagnostics.
            System.err.println("Release signing failed (" + e.getClass().getSimpleName() + ")");
            System.exit(1);
        }
    }

    private static void generate(Path privateFile, Path publicFile) throws Exception {
        if (Files.exists(privateFile) || Files.exists(publicFile)) {
            throw new IOException("Key files already exist");
        }
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.writeString(
                privateFile,
                Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\n",
                StandardOpenOption.CREATE_NEW);
        Files.writeString(
                publicFile,
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) + "\n",
                StandardOpenOption.CREATE_NEW);
        System.out.println("Created release signing key files. Keep the private file secret.");
    }

    private static void process(String[] args) throws Exception {
        String version = args[1];
        Path artifact = Path.of(args[2]);
        Path signatureFile = Path.of(args[3]);
        byte[] publicBytes = Base64.getDecoder().decode(read(Path.of(args[4]), 128).strip());
        var publicKey =
                KeyFactory.getInstance("Ed25519")
                        .generatePublic(new X509EncodedKeySpec(publicBytes));
        byte[] payload = payload(version, artifact, publicBytes);
        var verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(payload);
        if (args[0].equals("sign")) {
            String secret = System.getenv("RELEASE_SIGNING_KEY");
            if (secret == null || secret.length() > 256)
                throw new IllegalArgumentException("Missing signing key");
            var key =
                    KeyFactory.getInstance("Ed25519")
                            .generatePrivate(
                                    new PKCS8EncodedKeySpec(
                                            Base64.getDecoder().decode(secret.strip())));
            var signer = Signature.getInstance("Ed25519");
            signer.initSign(key);
            signer.update(payload);
            byte[] signature = signer.sign();
            if (!verifier.verify(signature))
                throw new IllegalArgumentException("Signing key mismatch");
            Files.write(signatureFile, signature, StandardOpenOption.CREATE_NEW);
            System.out.println("Signed release artifact.");
        } else {
            if (Files.size(signatureFile) != 64
                    || !verifier.verify(Files.readAllBytes(signatureFile))) {
                throw new IllegalArgumentException("Invalid signature");
            }
            System.out.println("Verified release signature.");
        }
    }

    private static String read(Path file, int limit) throws IOException {
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("File too large");
            return new String(bytes, StandardCharsets.US_ASCII);
        }
    }

    private static byte[] payload(String version, Path artifact, byte[] publicBytes)
            throws Exception {
        if (version.length() > 64
                || !version.matches(
                        "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-(alpha|beta|rc)\\.([1-9][0-9]*))?")) {
            throw new IllegalArgumentException("Invalid version");
        }
        for (String part : version.split("[.-]")) {
            if (Character.isDigit(part.charAt(0))) Integer.parseInt(part);
        }
        String name = "mithrilpf-" + version + ".jar";
        long size = Files.size(artifact);
        if (!artifact.getFileName().toString().equals(name)
                || size <= 0
                || size > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid release artifact");
        }
        // Prevent accidentally shipping a build with a different updater trust anchor.
        try (var zip = new ZipFile(artifact.toFile())) {
            try (var input = zip.getInputStream(zip.getEntry(PUBLIC_RESOURCE))) {
                byte[] embedded = input.readNBytes(129);
                if (embedded.length > 128
                        || !MessageDigest.isEqual(
                                publicBytes,
                                Base64.getDecoder()
                                        .decode(
                                                new String(embedded, StandardCharsets.US_ASCII)
                                                        .strip()))) {
                    throw new IllegalArgumentException("Packaged signing key mismatch");
                }
            }
            try (var input =
                    zip.getInputStream(zip.getEntry("assets/mithrilpf/build.properties"))) {
                byte[] bytes = input.readNBytes(1025);
                if (bytes.length > 1024) throw new IOException("Invalid build marker");
                var properties = new Properties();
                properties.load(new java.io.ByteArrayInputStream(bytes));
                if (!version.equals(properties.getProperty("version"))
                        || !"true".equals(properties.getProperty("officialRelease"))) {
                    throw new IllegalArgumentException("Not an official release build");
                }
            }
        }
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(artifact)) {
            byte[] bytes = new byte[8192];
            long total = 0;
            int count;
            while ((count = input.read(bytes)) != -1) {
                total += count;
                if (total > size) throw new IOException("Artifact changed");
                digest.update(bytes, 0, count);
            }
            if (total != size) throw new IOException("Artifact changed");
        }
        String text =
                "MithrilPF release signature v1\n"
                        + "repository=MithrilAddons/mithrilpf\n"
                        + "version="
                        + version
                        + "\n"
                        + "artifact="
                        + name
                        + "\n"
                        + "size="
                        + size
                        + "\n"
                        + "sha256="
                        + HexFormat.of().formatHex(digest.digest())
                        + "\n";
        return text.getBytes(StandardCharsets.US_ASCII);
    }
}
