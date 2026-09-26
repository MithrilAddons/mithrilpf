package dev.mithril.mithrilpf.update;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;

/** Standalone JDK-only helper. No network, shell, registry or Minecraft dependencies. */
public final class UpdateInstaller {
    private static final long MAX_SIZE = 16 * 1024 * 1024;

    public static void main(String[] args) {
        try {
            if (args.length != 6) throw new IOException("Invalid updater arguments");
            long pid = Long.parseLong(args[0]);
            long start = Long.parseLong(args[1]);
            waitForExit(pid, start, 120);
            install(Path.of(args[2]), Path.of(args[3]), args[4], args[5]);
            System.out.println("MithrilPF update installed. Previous JAR retained in backups.");
        } catch (Exception e) {
            System.err.println("MithrilPF update not installed: " + e.getClass().getSimpleName());
            System.exit(1);
        }
    }

    public static void waitForExit(long pid, long expectedStart, long seconds) throws Exception {
        if (pid == ProcessHandle.current().pid() || seconds <= 0) {
            throw new IOException("Invalid parent");
        }
        var parent = ProcessHandle.of(pid);
        if (parent.isPresent() && parent.get().isAlive()) {
            var instant = parent.get().info().startInstant().orElseThrow();
            if (instant.toEpochMilli() != expectedStart) throw new IOException("Parent changed");
            parent.get().onExit().get(seconds, TimeUnit.SECONDS);
        }
    }

    public static void install(Path target, Path staged, String oldHash, String newHash)
            throws Exception {
        install(
                target,
                staged,
                oldHash,
                newHash,
                (source, destination) ->
                        Files.move(
                                source,
                                destination,
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING));
    }

    @FunctionalInterface
    public interface Replace {
        void move(Path source, Path destination) throws IOException;
    }

    /** The replacement seam allows deterministic failure tests, without touching installed mods. */
    public static void install(
            Path target, Path staged, String oldHash, String newHash, Replace replace)
            throws Exception {
        target = target.toAbsolutePath().normalize();
        staged = staged.toAbsolutePath().normalize();
        if (!oldHash.matches("[a-f0-9]{64}") || !newHash.matches("[a-f0-9]{64}")) {
            throw new IOException("Invalid hashes");
        }
        Path mods = target.getParent();
        if (mods == null
                || !"mods".equals(mods.getFileName().toString())
                || !target.getFileName().toString().endsWith(".jar")) {
            throw new IOException("Target is not an installed mod");
        }
        Path updates = mods.getParent().resolve("config/mithrilpf/updates");
        if (!staged.startsWith(updates)
                || staged.getParent().equals(updates)
                || !staged.getFileName().toString().equals("update.jar")) {
            throw new IOException("Invalid staging path");
        }
        requireRegular(target);
        requireRegular(staged);
        if (!target.toRealPath().equals(target) || !staged.toRealPath().equals(staged)) {
            throw new IOException("Symlinked update paths are not supported");
        }
        Path lock = updates.resolve("install.lock");
        if (Files.isSymbolicLink(lock)) throw new IOException("Invalid lock");
        try (var channel =
                        FileChannel.open(
                                lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                var held = channel.tryLock()) {
            if (held == null) throw new IOException("Another update is installing");
            verify(target, oldHash);
            verify(staged, newHash);
            Path backups = updates.resolve("backups");
            Files.createDirectories(backups);
            if (!backups.toRealPath().equals(backups)) throw new IOException("Invalid backup path");
            Path backup = backups.resolve(oldHash + ".jar.backup");
            if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                verify(backup, oldHash);
            } else {
                Files.copy(target, backup);
                verify(backup, oldHash);
            }
            // Copy onto the same filesystem. An unsupported/failed atomic replacement leaves the
            // original installed. Keeping the filename avoids a duplicate-mod window on crashes.
            Path temp = Files.createTempFile(mods, ".mithrilpf-update-", ".tmp");
            try {
                Files.copy(staged, temp, StandardCopyOption.REPLACE_EXISTING);
                verify(temp, newHash);
                verify(target, oldHash);
                replace.move(temp, target);
            } finally {
                Files.deleteIfExists(temp);
            }
            Files.deleteIfExists(staged);
        }
    }

    private static void requireRegular(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) <= 0
                || Files.size(path) > MAX_SIZE) {
            throw new IOException("Invalid update file");
        }
    }

    private static void verify(Path path, String expected) throws Exception {
        requireRegular(path);
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = new byte[8192];
            long total = 0;
            int count;
            while ((count = input.read(bytes)) != -1) {
                total += count;
                if (total > MAX_SIZE) throw new IOException("Update file grew");
                digest.update(bytes, 0, count);
            }
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(expected)) {
            throw new IOException("File changed");
        }
    }
}
