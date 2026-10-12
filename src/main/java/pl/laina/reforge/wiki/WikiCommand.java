package pl.laina.reforge.wiki;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public final class WikiCommand implements CommandExecutor, TabCompleter {
    private final WikiDialogService dialogs;
    private final WikiCatalog catalog;
    private final WikiIndexService index;

    public WikiCommand(WikiDialogService dialogs, WikiCatalog catalog, WikiIndexService index) {
        this.dialogs = dialogs;
        this.catalog = catalog;
        this.index = index;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Ta komenda wymaga gracza.");
            return true;
        }
        execute(player, label, args);
        return true;
    }

    private void execute(Player player, String label, String[] args) {
        if (label.substring(label.lastIndexOf(':') + 1).equalsIgnoreCase("wikici") && args.length == 0) {
            dialogs.openHeld(player);
            return;
        }
        if (args.length == 0) {
            dialogs.showHome(player);
        } else if (args.length == 1 && (args[0].equalsIgnoreCase("reka") || args[0].equalsIgnoreCase("held") || args[0].equalsIgnoreCase("ci"))) {
            dialogs.openHeld(player);
        } else {
            dialogs.openQuery(player, String.join(" ", args));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> matches = new java.util.ArrayList<>(combinedSuggestions(catalog.titles(), index.titles(), args[0], 20));
            if ("reka".startsWith(args[0].toLowerCase(java.util.Locale.ROOT))) matches.addFirst("reka");
            return matches.stream().limit(20).toList();
        }
        String query = String.join(" ", args);
        List<String> matches = combinedSuggestions(catalog.titles(), index.titles(), query, 20);
        return matches.stream().map(title -> completionSuffix(title, args)).filter(value -> !value.isBlank()).distinct().toList();
    }

    static List<String> combinedSuggestions(List<String> catalog, List<String> index, String query, int limit) {
        var titles = new java.util.ArrayList<>(catalog);
        titles.addAll(index);
        return WikiSearch.suggestions(titles, query, limit);
    }

    static String completionSuffix(String title, String[] args) {
        String[] words = title.replace('_', ' ').strip().split("\\s+");
        int completed = Math.max(0, args.length - 1);
        if (completed >= words.length) return "";
        for (int i = 0; i < completed; i++) {
            if (!WikiCatalog.normalize(words[i]).equals(WikiCatalog.normalize(args[i]))) return "";
        }
        return String.join(" ", java.util.Arrays.copyOfRange(words, completed, words.length));
    }
}
