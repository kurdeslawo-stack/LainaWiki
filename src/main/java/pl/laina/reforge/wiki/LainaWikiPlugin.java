package pl.laina.reforge.wiki;

import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class LainaWikiPlugin extends JavaPlugin {

    private WikiArticleService articleService;
    private WikiIndexService index;
    private WikiFavorites favorites;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        WikiCatalog catalog = WikiCatalog.load(this);
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
}
