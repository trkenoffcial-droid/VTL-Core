package com.kevin.tiertagger.config;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Loads and saves one config object as JSON.
 *
 * <ul>
 *     <li>A missing file gives the defaults.</li>
 *     <li>A file that cannot be parsed is kept as {@code <name>.broken} (so nothing the player
 *     set is silently lost) and the defaults are used.</li>
 *     <li>Saving goes through a temp file and a move, so a crash mid-write never leaves a
 *     half-written config behind.</li>
 * </ul>
 *
 * <p>No Minecraft types: {@code ConfigCheck} runs it without a game.</p>
 */
public final class JsonConfigStore<T> {
    private static final Logger LOG = Logger.getLogger("VTL");

    private final Path file;
    private final Class<T> type;
    private final Supplier<T> defaults;
    private final Gson gson;

    public JsonConfigStore(Path file, Class<T> type, Supplier<T> defaults, Gson gson) {
        this.file = file;
        this.type = type;
        this.defaults = defaults;
        this.gson = gson;
    }

    public Path file() {
        return file;
    }

    public boolean exists() {
        return Files.isRegularFile(file);
    }

    public T load() {
        if (!exists()) return defaults.get();
        try {
            T value = gson.fromJson(Files.readString(file, StandardCharsets.UTF_8), type);
            if (value != null) return value;
            throw new JsonParseException("empty file");
        } catch (IOException | JsonParseException e) {
            Path aside = file.resolveSibling(file.getFileName() + ".broken");
            LOG.warning("Could not read " + file + " (" + e.getMessage() + "); keeping it as " + aside.getFileName() + " and using the defaults");
            try {
                Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailed) {
                LOG.warning("Could not move the broken config aside: " + moveFailed);
            }
            return defaults.get();
        }
    }

    public void save(T value) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(tmp, gson.toJson(value), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            LOG.warning("Could not save " + file + ": " + e);
        }
    }
}
