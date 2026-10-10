package com.kevin.tiertagger.queue;

import com.kevin.tiertagger.TierTagger;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * Keeps the queue token in its own file, {@code config/tiertagger-queue-token.txt}, instead of
 * {@code tiertagger.toml}: players share config folders and modpacks, and a token in there would
 * let anyone join queues as them. The file is owner-only where the OS supports it.
 *
 * <p>The token is never shown in the UI and never logged.</p>
 */
public final class QueueTokenStore {
    private QueueTokenStore() {}

    private static final String FILE_NAME = "tiertagger-queue-token.txt";
    private static volatile String cached;
    private static volatile boolean loaded;

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /** The stored token, or null when this install is not linked. */
    public static String get() {
        if (!loaded) {
            synchronized (QueueTokenStore.class) {
                if (!loaded) {
                    cached = read();
                    loaded = true;
                }
            }
        }
        return cached;
    }

    public static boolean isLinked() {
        return get() != null;
    }

    /** Stores a new token, or deletes the file when {@code token} is null. */
    public static synchronized void set(String token) {
        String value = token == null || token.isBlank() ? null : token.trim();
        cached = value;
        loaded = true;
        Path file = path();
        try {
            if (value == null) {
                Files.deleteIfExists(file);
                return;
            }
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.writeString(tmp, value, StandardCharsets.UTF_8);
            restrict(tmp);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            TierTagger.getLogger().warn("Could not save the queue link token: {}", e.toString());
        }
    }

    /** Deletes the stored token only if it is still {@code expected}. Returns true when it did. */
    public static synchronized boolean clearIf(String expected) {
        if (expected == null || !expected.equals(get())) return false;
        set(null);
        return true;
    }

    /**
     * Moves a token that an older build kept in {@code tiertagger.toml} into the token file.
     * Returns true when the config changed and needs saving.
     */
    public static boolean migrateFromConfig() {
        var config = TierTagger.getManager().getConfig();
        String legacy = config.getQueueToken();
        if (legacy == null) return false;
        if (get() == null) set(legacy);
        config.setQueueToken(null);
        return true;
    }

    private static String read() {
        Path file = path();
        try {
            if (!Files.isRegularFile(file)) return null;
            String value = Files.readString(file, StandardCharsets.UTF_8).trim();
            return value.isEmpty() ? null : value;
        } catch (IOException e) {
            TierTagger.getLogger().warn("Could not read the queue link token: {}", e.toString());
            return null;
        }
    }

    private static void restrict(Path file) {
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows: the user profile folder is already private to the user.
        }
    }
}
