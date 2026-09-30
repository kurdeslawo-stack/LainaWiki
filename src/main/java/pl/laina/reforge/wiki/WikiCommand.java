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
        } else if (args.length == 1 && (args[0].equalsIgnoreCase("reka") || args[0].equalsIgnoreCase("held"))) {
            dialogs.openHeld(player);
        } else {
            dialogs.openQuery(player, String.join(" ", args));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> matches = new java.util.ArrayList<>(catalog.suggestions(args[0], 20));
            if ("reka".startsWith(args[0].toLowerCase())) matches.addFirst("reka");
            index.suggestions(args[0], 20).stream().filter(title -> !matches.contains(title))
                    .limit(Math.max(0, 20 - matches.size())).forEach(matches::add);
            return matches;
        }
        String query = String.join(" ", args);
        List<String> matches = new java.util.ArrayList<>(catalog.suggestions(query, 20));
        index.suggestions(query, 20).stream().filter(title -> !matches.contains(title))
                .limit(Math.max(0, 20 - matches.size())).forEach(matches::add);
        return matches;
    }
}
