package pl.laina.reforge.wiki;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class LainaWikiPlugin extends JavaPlugin {

    private WikiArticleService articleService;
    private WikiIndexService index;
    private WikiFavorites favorites;
    private WikiCatalog catalog;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        catalog = WikiCatalog.load(this);
        articleService = new WikiArticleService(this);
        index = new WikiIndexService(this);
        favorites = new WikiFavorites(getDataFolder().toPath().resolve("favorites"));
        WikiDialogService dialogs = new WikiDialogService(this, catalog, articleService, index, favorites);
        getServer().getPluginManager().registerEvents(dialogs, this);

        PluginCommand command = getCommand("wiki");
        if (command == null) {
            getLogger().severe("Brak komendy wiki w plugin.yml.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        WikiCommand wikiCommand = new WikiCommand(dialogs, catalog, index);
        command.setExecutor(wikiCommand);
        command.setTabCompleter(wikiCommand);
        index.start();
        getLogger().info("Księga Lainy uruchomiona.");
    }

    @Override
    public void onDisable() {
        if (index != null) index.close();
        if (articleService != null) {
            articleService.close();
        }
        if (favorites != null) favorites.close();
    }

    /** Main-thread API, v1. Only Java types cross plugin classloaders; loading shares the article cache. */
    public java.util.concurrent.CompletableFuture<java.util.Map<String, String>> describeHeldItem(
            org.bukkit.entity.Player player, boolean acquisition) {
        if (!org.bukkit.Bukkit.isPrimaryThread()) throw new IllegalStateException("Call on server thread");
        if (!isEnabled() || !player.hasPermission("lainawiki.use")) {
            return java.util.concurrent.CompletableFuture.completedFuture(java.util.Map.of("status", "unavailable"));
        }
        var item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir()) return java.util.concurrent.CompletableFuture.completedFuture(java.util.Map.of("status", "empty"));
        var found = catalog.find(item);
        String title = found.map(WikiEntry::title).orElse(null);
        var meta = item.getItemMeta();
        if (title == null && meta != null && meta.hasCustomModelData() && meta.hasDisplayName()) {
            String name = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(meta.displayName());
            title = index.exact(name).orElse(null);
        }
        if (title == null) return java.util.concurrent.CompletableFuture.completedFuture(java.util.Map.of("status", "unknown"));
        return articleService.load(title).handle((result, failure) -> failure == null
                ? WikiItemInsight.describe(result.value(), acquisition, result.stale())
                : java.util.Map.of("status", WikiArticleService.isMissingArticle(failure) ? "missing" : "unavailable"));
    }
}
