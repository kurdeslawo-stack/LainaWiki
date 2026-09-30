package pl.laina.reforge.wiki;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class WikiCatalog {
    private final Map<String, WikiEntry> byRuntimeIdentity;
    private final List<WikiEntry> entries;
    private final Map<String, WikiEntry> byTitle;

    private WikiCatalog(Map<String, WikiEntry> byRuntimeIdentity, List<WikiEntry> entries) {
        this.byRuntimeIdentity = Map.copyOf(byRuntimeIdentity);
        this.entries = List.copyOf(entries);
        Map<String, WikiEntry> indexed = new LinkedHashMap<>();
        entries.forEach(entry -> indexed.putIfAbsent(normalize(entry.title()), entry));
        byTitle = Map.copyOf(indexed);
    }

    public static WikiCatalog load(LainaWikiPlugin plugin) {
        try (var stream = plugin.getResource("items.yml")) {
            if (stream == null) {
                plugin.getLogger().severe("Brak items.yml - /wiki nie rozpozna customowych itemow.");
                return new WikiCatalog(Map.of(), List.of());
            }
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            ConfigurationSection root = yaml.getConfigurationSection("items");
            if (root == null) {
                return new WikiCatalog(Map.of(), List.of());
            }

            Map<String, WikiEntry> identities = new LinkedHashMap<>();
            List<WikiEntry> entries = new ArrayList<>();
            for (String key : root.getKeys(false)) {
                ConfigurationSection section = root.getConfigurationSection(key);
                if (section == null) continue;
                String title = section.getString("wiki", "").trim();
                if (title.isBlank()) title = section.getString("name", "").trim();
                if (title.isBlank()) continue;
                Material material = Material.matchMaterial(section.getString("material", ""));
                int cmd = section.getInt("cmd", -1);
                if (material == null || cmd < 0) continue;
                WikiEntry entry = new WikiEntry(material, cmd, section.getString("model", ""), title,
                        WikiCategory.fromType(section.getString("type", "")));
                identities.put(identity(material, cmd), entry);
                entries.add(entry);
            }
            entries.sort(Comparator.comparing(WikiEntry::title, String.CASE_INSENSITIVE_ORDER));
            plugin.getLogger().info("Ksiega Lainy: zaladowano " + entries.size() + " mapowan item -> Wiki.");
            return new WikiCatalog(identities, entries);
        } catch (IOException exception) {
            plugin.getLogger().severe("Nie udalo sie odczytac items.yml: " + exception.getMessage());
            return new WikiCatalog(Map.of(), List.of());
        }
    }

    public Optional<WikiEntry> find(ItemStack item) {
        if (item == null || item.getType().isAir()) return Optional.empty();
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasCustomModelData()) return Optional.empty();
        return Optional.ofNullable(byRuntimeIdentity.get(identity(item.getType(), meta.getCustomModelData())));
    }

    public Optional<WikiEntry> search(String query) {
        String needle = normalize(query);
        if (needle.isBlank()) return Optional.empty();
        return exact(query)
                .or(() -> entries.stream().filter(entry -> normalize(entry.title()).startsWith(needle)).findFirst())
                .or(() -> entries.stream().filter(entry -> normalize(entry.title()).contains(needle)).findFirst());
    }

    public Optional<WikiEntry> exact(String query) {
        String needle = normalize(query);
        if (needle.isBlank()) return Optional.empty();
        return Optional.ofNullable(byTitle.get(needle));
    }

    public List<String> suggestions(String query, int limit) {
        String needle = normalize(query);
        return entries.stream()
                .map(WikiEntry::title)
                .distinct()
                .filter(title -> needle.isBlank() || normalize(title).contains(needle))
                .limit(limit)
                .toList();
    }

    public int size() {
        return entries.size();
    }

    public boolean containsTitle(String title) {
        String normalized = normalize(title);
        return byTitle.containsKey(normalized);
    }

    public List<WikiEntry> entries(WikiCategory category) {
        Map<String, WikiEntry> uniqueTitles = new LinkedHashMap<>();
        for (WikiEntry entry : entries) {
            if (entry.category() == category) {
                uniqueTitles.putIfAbsent(normalize(entry.title()), entry);
            }
        }
        return List.copyOf(uniqueTitles.values());
    }

    public Optional<WikiEntry> random() {
        if (entries.isEmpty()) return Optional.empty();
        return Optional.of(entries.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(entries.size())));
    }

    static String normalize(String value) {
        String decomposed = Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "")
                .replace('_', ' ')
                .toLowerCase(Locale.ROOT)
                .replace('ł', 'l')
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String identity(Material material, int cmd) {
        return material.getKey().getKey() + ':' + cmd;
    }
}
