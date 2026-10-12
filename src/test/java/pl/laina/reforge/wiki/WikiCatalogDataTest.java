package pl.laina.reforge.wiki;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WikiCatalogDataTest {
    @Test void bundledMappingsHaveValidUniqueRuntimeIdentitiesAndTitles() throws Exception {
        try (var stream = getClass().getResourceAsStream("/items.yml")) {
            assertNotNull(stream);
            var root = YamlConfiguration.loadConfiguration(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getConfigurationSection("items");
            assertNotNull(root);
            var identities = new HashSet<String>();
            var titles = new HashSet<String>();
            int mapped = 0;
            for (String key : root.getKeys(false)) {
                var entry = root.getConfigurationSection(key);
                assertNotNull(entry, key);
                assertFalse(entry.getString("material", "").isBlank(), key);
                assertTrue(entry.getInt("cmd", -1) >= 0, key);
                String title = entry.getString("wiki", "");
                if (title.isBlank()) title = entry.getString("name", "");
                if (!title.isBlank()) {
                    titles.add(WikiCatalog.normalize(title));
                    mapped++;
                }
                assertTrue(identities.add(entry.getString("material") + ":" + entry.getInt("cmd")), "Duplicate identity: " + key);
            }
            assertTrue(titles.size() > 0);
            System.out.println("CATALOG_AUDIT totalModels=" + identities.size() + " mapped=" + mapped
                    + " unmapped=" + (identities.size() - mapped) + " uniqueTitles=" + titles.size());
        }
    }
}
