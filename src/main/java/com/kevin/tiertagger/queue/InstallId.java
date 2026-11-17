package com.kevin.tiertagger.queue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One random id per game install, kept in the config folder. The website counts installs by it;
 * nothing in it points at a player or a machine.
 */
public final class InstallId {
    private InstallId() {}

    public static final String FILE_NAME = "vtl-install-id.txt";

    private static final Pattern V4 =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");

    private static volatile String cached;

    public static boolean isValid(String id) {
        return id != null && V4.matcher(id).matches();
    }

    /** Reads the stored id, or creates and saves a new one. Never throws; an unsaved id is still returned. */
    public static String loadOrCreate(Path file) {
        try {
            if (Files.isRegularFile(file)) {
                String stored = Files.readString(file, StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
                if (isValid(stored)) return stored;
            }
        } catch (IOException | RuntimeException ignored) {
            // Unreadable file: replace it below.
        }
        String id = UUID.randomUUID().toString();
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) Files.createDirectories(parent);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, id + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException ignored) {
            // Read-only or broken config folder: this session uses an id that is not saved.
        }
        return id;
    }

    /** {@link #loadOrCreate} once per game start. */
    public static String cached(Path file) {
        String id = cached;
        if (id != null) return id;
        synchronized (InstallId.class) {
            if (cached == null) cached = loadOrCreate(file);
            return cached;
        }
    }
}
