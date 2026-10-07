package dev.spotifychat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Adds the settings button in Mod Menu. Only loaded when Mod Menu is installed. */
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SpotifyConfigScreen::new;
    }
}
