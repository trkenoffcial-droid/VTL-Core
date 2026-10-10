package com.kevin.tiertagger.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.nio.file.Path;
import java.util.logging.Logger;

/**
 * The mod's settings, kept in {@code config/vtl.json}.
 *
 * <p>On the very first start of 3.0 there is no {@code vtl.json} yet: the values the player set
 * in the ukulib-era {@code tiertagger.toml} are read once and written to {@code vtl.json}.
 * The old file is left alone.</p>
 *
 * <p>Same two calls as the ukulib manager it replaces ({@code getConfig()}, {@code saveConfig()}),
 * so the code that reads settings did not have to change.</p>
 */
public final class VtlConfig {
    private static final Logger LOG = Logger.getLogger("VTL");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final JsonConfigStore<TierTaggerConfig> store;
    private final TierTaggerConfig config;

    public VtlConfig(Path configDir) {
        this.store = new JsonConfigStore<>(configDir.resolve("vtl.json"), TierTaggerConfig.class, TierTaggerConfig::new, GSON);
        if (store.exists()) {
            this.config = store.load();
        } else {
            this.config = importLegacy(configDir.resolve("tiertagger.toml"));
            this.config.normalize();
            store.save(this.config);
            return;
        }
        this.config.normalize();
    }

    private static TierTaggerConfig importLegacy(Path toml) {
        JsonObject legacy = LegacyTomlImport.read(toml);
        if (legacy.size() == 0) return new TierTaggerConfig();
        try {
            TierTaggerConfig imported = GSON.fromJson(legacy, TierTaggerConfig.class);
            LOG.info("Imported settings from " + toml.getFileName());
            return imported == null ? new TierTaggerConfig() : imported;
        } catch (JsonParseException | IllegalStateException e) {
            LOG.warning("Could not import " + toml.getFileName() + " (" + e.getMessage() + "); using the defaults");
            return new TierTaggerConfig();
        }
    }

    public TierTaggerConfig getConfig() {
        return config;
    }

    public void saveConfig() {
        store.save(config);
    }
}
