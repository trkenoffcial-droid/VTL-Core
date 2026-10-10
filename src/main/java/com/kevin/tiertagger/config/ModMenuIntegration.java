package com.kevin.tiertagger.config;

import com.kevin.tiertagger.hub.HubTab;
import com.kevin.tiertagger.hub.VtlHubScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * The settings button in ModMenu. Only ModMenu loads this class, so the mod works the same
 * without ModMenu installed.
 */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new VtlHubScreen(parent, HubTab.Id.SETTINGS);
    }
}
