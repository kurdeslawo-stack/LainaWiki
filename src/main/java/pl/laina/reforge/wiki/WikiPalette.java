package pl.laina.reforge.wiki;

import net.kyori.adventure.text.format.TextColor;
import org.bukkit.configuration.ConfigurationSection;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

final class WikiPalette {
    private final Map<String, TextColor> colors = new HashMap<>();
    static WikiPalette defaults() { return new WikiPalette(null, null); }
    WikiPalette(ConfigurationSection config, Logger logger) {
        if (config == null) return;
        ConfigurationSection section = config.getConfigurationSection("wiki.colors");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            String value = section.getString(key, "");
            TextColor color = value.matches("#[0-9a-fA-F]{6}") ? TextColor.fromHexString(value) : null;
            if (color != null) colors.put(key, color);
            else if (logger != null) logger.warning("Niepoprawny kolor wiki.colors." + key + "; używam domyślnego. Format: #RRGGBB");
        }
    }
    TextColor color(String key, TextColor fallback) { return colors.getOrDefault(key, fallback); }
}
