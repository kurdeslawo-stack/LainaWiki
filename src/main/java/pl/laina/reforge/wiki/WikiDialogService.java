package pl.laina.reforge.wiki;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class WikiDialogService {
    private static final int CONTENT_WIDTH = 360;
    private static final int ICON_SIZE = 24;
    private static final int PAGE_SIZE = 6;
    private static final int ARTICLE_PAGE_LENGTH = 1250;

    private final LainaWikiPlugin plugin;
    private final WikiCatalog catalog;
    private final WikiArticleService articles;
    private final WikiIndexService index;
    private final Map<UUID, Deque<WikiEntry>> recent = new ConcurrentHashMap<>();

    public WikiDialogService(LainaWikiPlugin plugin, WikiCatalog catalog,
                             WikiArticleService articles, WikiIndexService index) {
        this.plugin = plugin;
        this.catalog = catalog;
        this.articles = articles;
        this.index = index;
    }

    public void showHome(Player player) {
        ActionButton search = ActionButton.builder(Component.text("Szukaj przedmiotu", NamedTextColor.GOLD, TextDecoration.BOLD))
                .tooltip(Component.text("Wyszukaj przedmiot w Księdze Lainy"))
                .width(170)
                .action(DialogAction.customClick((response, audience) -> {
                    if (audience instanceof Player target) {
                        String query = response.getText("query");
                        plugin.getServer().getScheduler().runTask(plugin, () -> openQuery(target, query));
                    }
                }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build()))
                .build();
        List<ActionButton> actions = new ArrayList<>();
        actions.add(search);
        actions.add(action("Przedmiot w ręce", "Otwórz kartę trzymanego przedmiotu", NamedTextColor.AQUA, this::openHeld));
        actions.add(action("Przeglądaj działy", "Broń, pancerze, narzędzia i inne", NamedTextColor.AQUA, this::showCategories));
        actions.add(action("Losowa karta", "Otwórz niespodziewaną kartę z księgi", NamedTextColor.LIGHT_PURPLE, this::openRandom));
        if (!recent.getOrDefault(player.getUniqueId(), new ArrayDeque<>()).isEmpty()) {
            actions.add(action("Ostatnio oglądane", "Wróć do niedawno otwartych kart", NamedTextColor.GRAY, this::showHistory));
        }

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text("Księga Lainy", NamedTextColor.AQUA, TextDecoration.BOLD))
                        .externalTitle(Component.text("Księga Lainy"))
                        .canCloseWithEscape(true)
                        .pause(false)
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        .body(List.of(DialogBody.plainMessage(homeIntro(), CONTENT_WIDTH)))
                        .inputs(List.of(DialogInput.text("query", Component.text("WPISZ NAZWĘ PRZEDMIOTU", NamedTextColor.GOLD))
                                .width(CONTENT_WIDTH).maxLength(80).build()))
                        .build())
                .type(DialogType.multiAction(actions).columns(2).build()));
        player.showDialog(dialog);
    }

    public void showCategories(Player player) {
        List<ActionButton> actions = new ArrayList<>();
        for (WikiCategory category : WikiCategory.values()) {
            int count = catalog.entries(category).size();
            actions.add(ActionButton.builder(Component.text("• ", category.color())
                            .append(Component.text(category.label(), NamedTextColor.WHITE))
                            .append(Component.text("  ·  " + count, NamedTextColor.GRAY)))
                    .tooltip(Component.text("Przeglądaj " + category.label().toLowerCase() + " (" + count + ")"))
                    .width(170)
                    .action(click(target -> showCategory(target, category, 0))).build());
        }
        int wikiOnly = index.unmapped(catalog).size();
        if (wikiOnly > 0) {
            actions.add(action("Strony z Wiki  ·  " + wikiOnly,
                    "Artykuły, które nie mają jeszcze pewnego ID itemu", NamedTextColor.LIGHT_PURPLE,
                    target -> showWikiPages(target, 0)));
        }
        actions.add(action("← Do księgi", "Wróć na stronę główną", NamedTextColor.GRAY, this::showHome));
        showMenu(player, "Ścieżki księgi", Component.text("WYBIERZ DZIAŁ", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text("\n" + catalog.size() + " modeli  •  6 działów", NamedTextColor.AQUA)), actions);
    }

    private void showWikiPages(Player player, int requestedPage) {
        List<String> titles = index.unmapped(catalog);
        if (titles.isEmpty()) {
            showCategories(player);
            return;
        }
        int lastPage = Math.max(0, (titles.size() - 1) / PAGE_SIZE);
        int page = Math.clamp(requestedPage, 0, lastPage);
        List<ActionButton> actions = new ArrayList<>();
        int start = page * PAGE_SIZE;
        for (int offset = start; offset < Math.min(start + PAGE_SIZE, titles.size()); offset++) {
            String title = titles.get(offset);
            actions.add(ActionButton.builder(Component.text(shortLabel(title), NamedTextColor.WHITE))
                    .tooltip(Component.text(title)).width(170)
                    .action(click(target -> openWikiTitle(target, title))).build());
        }
        if (page > 0) actions.add(action("← Poprzednia", "Poprzednia strona", NamedTextColor.GRAY,
                target -> showWikiPages(target, page - 1)));
        if (page < lastPage) actions.add(action("Następna →", "Następna strona", NamedTextColor.GOLD,
                target -> showWikiPages(target, page + 1)));
        actions.add(action("← Wszystkie działy", "Wróć do działów", NamedTextColor.GRAY, this::showCategories));
        Component intro = Component.text("ODKRYTE NA WIKI", NamedTextColor.LIGHT_PURPLE, TextDecoration.BOLD)
                .append(Component.text("\n" + titles.size() + " stron  •  strona " + (page + 1)
                        + " z " + (lastPage + 1), NamedTextColor.GRAY));
        showMenu(player, "Strony z Wiki", intro, actions);
    }

    private void openWikiTitle(Player player, String title) {
        WikiEntry entry = WikiEntry.wikiOnly(title);
        openEntry(player, entry, entry.createIcon());
    }

    public void showCategory(Player player, WikiCategory category, int requestedPage) {
        List<WikiEntry> entries = catalog.entries(category);
        int lastPage = Math.max(0, (entries.size() - 1) / PAGE_SIZE);
        int page = Math.clamp(requestedPage, 0, lastPage);
        List<ActionButton> actions = new ArrayList<>();
        int start = page * PAGE_SIZE;
        for (int index = start; index < Math.min(start + PAGE_SIZE, entries.size()); index++) {
            WikiEntry entry = entries.get(index);
            actions.add(ActionButton.builder(Component.text(shortLabel(entry.title()), category.color()))
                    .tooltip(Component.text(entry.title()))
                    .width(170)
                    .action(click(target -> openEntry(target, entry, entry.createIcon()))).build());
        }
        if (page > 0) actions.add(action("← Poprzednia", "Poprzednia strona", NamedTextColor.GRAY,
                target -> showCategory(target, category, page - 1)));
        if (page < lastPage) actions.add(action("Następna →", "Następna strona", NamedTextColor.GOLD,
                target -> showCategory(target, category, page + 1)));
        actions.add(action("← Wszystkie działy", "Wróć do działów", NamedTextColor.GRAY, this::showCategories));
        Component intro = Component.text(category.label().toUpperCase(), category.color(), TextDecoration.BOLD)
                .append(Component.text("\n" + entries.size() + " kart  •  strona " + (page + 1) + " z " + (lastPage + 1), NamedTextColor.GRAY));
        showMenu(player, category.label(), intro, actions);
    }

    public void showHistory(Player player) {
        Deque<WikiEntry> history = recent.get(player.getUniqueId());
        if (history == null || history.isEmpty()) {
            showHome(player);
            return;
        }
        List<ActionButton> actions = new ArrayList<>();
        for (WikiEntry entry : history) {
            actions.add(ActionButton.builder(Component.text(shortLabel(entry.title()), NamedTextColor.AQUA))
                    .tooltip(Component.text(entry.title())).width(170)
                    .action(click(target -> openEntry(target, entry, entry.createIcon()))).build());
        }
        actions.add(action("← Do księgi", "Wróć na stronę główną", NamedTextColor.GRAY, this::showHome));
        showMenu(player, "Ostatnio oglądane", Component.text("TWÓJ ŚLAD W KSIĘDZE", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text("\nOstatnie 5 kart z tej sesji serwera.", NamedTextColor.GRAY)), actions);
    }

    public void openRandom(Player player) {
        catalog.random().ifPresentOrElse(
                entry -> openEntry(player, entry, entry.createIcon()),
                () -> showError(player, "Księga jest pusta", "Nie znaleziono żadnych kart."));
    }

    public void openHeld(Player player) {
        ItemStack held = player.getInventory().getItemInMainHand();
        catalog.find(held).ifPresentOrElse(
                entry -> openEntry(player, entry, held.clone()),
                () -> {
                    var meta = held == null ? null : held.getItemMeta();
                    if (meta != null && meta.hasCustomModelData() && meta.hasDisplayName()) {
                        String name = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
                        Optional<String> matched = index.exact(name);
                        if (matched.isPresent()) {
                            openEntry(player, WikiEntry.wikiOnly(matched.get()), held.clone());
                            return;
                        }
                    }
                    showError(player, "Nie rozpoznaję tego przedmiotu",
                            "Brak pewnego dopasowania ID lub nazwy do Wiki. Spróbuj wyszukać go ręcznie.");
                });
    }

    public void openQuery(Player player, String query) {
        if (query == null || query.isBlank()) {
            showHome(player);
            return;
        }
        catalog.exact(query).ifPresentOrElse(
                entry -> openEntry(player, entry, entry.createIcon()),
                () -> index.exact(query).ifPresentOrElse(
                        title -> openWikiTitle(player, title),
                        () -> catalog.search(query).ifPresentOrElse(
                                entry -> openEntry(player, entry, entry.createIcon()),
                                () -> index.search(query).ifPresentOrElse(
                                        title -> openWikiTitle(player, title),
                                        () -> showError(player, "Nie znaleziono przedmiotu", "Brak wyniku dla: " + query)))));
    }

    private void openEntry(Player player, WikiEntry entry, ItemStack icon) {
        var cached = articles.cached(entry.title());
        if (cached.isPresent()) {
            showArticle(player, entry, icon, cached.get(), 0);
            return;
        }
        player.sendActionBar(Component.text("Ładowanie artykułu „" + entry.title() + "”…", NamedTextColor.AQUA));
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                WikiArticleService.WikiArticle article = articles.fetch(entry.title());
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) showArticle(player, entry, icon, article, 0);
                });
            } catch (Exception exception) {
                plugin.getLogger().warning("Wiki: " + entry.title() + ": " + exception.getMessage());
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) showError(player, "Nie udało się pobrać artykułu", exception.getMessage());
                });
            }
        });
    }

    private void showArticle(Player player, WikiEntry entry, ItemStack icon,
                             WikiArticleService.WikiArticle article, int requestedPage) {
        remember(player, entry);
        List<String> pages = articlePages(article.text(), ARTICLE_PAGE_LENGTH);
        int page = Math.clamp(requestedPage, 0, pages.size() - 1);
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(articleIntro(article, page + 1, pages.size()), CONTENT_WIDTH));
        body.add(DialogBody.item(icon).showDecorations(false).showTooltip(true)
                .width(ICON_SIZE).height(ICON_SIZE).build());
        body.add(DialogBody.plainMessage(articleBody(pages.get(page), article.links()), CONTENT_WIDTH));
        String url = articleUrl(article.title());
        ActionButton back = button("Wróć", "Wróć do wyszukiwarki", ClickEvent.runCommand("/wiki"));
        ActionButton web = button("Pełny artykuł", url, ClickEvent.openUrl(url));
        List<ActionButton> actions = new ArrayList<>();
        if (page > 0) actions.add(action("← Poprzednia", "Poprzednia strona opisu", NamedTextColor.GRAY,
                target -> showArticle(target, entry, icon, article, page - 1)));
        if (page < pages.size() - 1) actions.add(action("Czytaj dalej →", "Następna strona opisu", NamedTextColor.GOLD,
                target -> showArticle(target, entry, icon, article, page + 1)));
        actions.add(back);
        actions.add(web);
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text(article.title(), NamedTextColor.GOLD, TextDecoration.BOLD))
                        .externalTitle(Component.text("Wiki: " + article.title()))
                        .canCloseWithEscape(true).pause(false)
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        .body(body).build())
                .type(DialogType.multiAction(actions).columns(2).build()));
        player.showDialog(dialog);
    }

    private void showError(Player player, String title, String message) {
        ActionButton back = button("Wróć", "Wróć do wyszukiwarki", ClickEvent.runCommand("/wiki"));
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text(title, NamedTextColor.RED))
                        .canCloseWithEscape(true).pause(false)
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        .body(List.of(DialogBody.plainMessage(
                                Component.text(message == null ? "Nieznany błąd." : message, NamedTextColor.GRAY),
                                CONTENT_WIDTH)))
                        .build())
                .type(DialogType.notice(back)));
        player.showDialog(dialog);
    }

    private ActionButton button(String label, String tooltip, ClickEvent<?> click) {
        return ActionButton.builder(Component.text(label))
                .tooltip(Component.text(tooltip)).width(170)
                .action(DialogAction.staticAction(click)).build();
    }

    private ActionButton action(String label, String tooltip, Consumer<Player> handler) {
        return action(label, tooltip, NamedTextColor.AQUA, handler);
    }

    private ActionButton action(String label, String tooltip, NamedTextColor color, Consumer<Player> handler) {
        return ActionButton.builder(Component.text(label, color))
                .tooltip(Component.text(tooltip)).width(170).action(click(handler)).build();
    }

    private DialogAction click(Consumer<Player> handler) {
        return DialogAction.customClick((response, audience) -> {
            if (audience instanceof Player target) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (target.isOnline()) handler.accept(target);
                });
            }
        }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build());
    }

    private void showMenu(Player player, String title, Component intro, List<ActionButton> actions) {
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text(title, NamedTextColor.AQUA, TextDecoration.BOLD))
                        .externalTitle(Component.text(title)).canCloseWithEscape(true).pause(false)
                        .afterAction(DialogBase.DialogAfterAction.NONE)
                        .body(List.of(DialogBody.plainMessage(intro, CONTENT_WIDTH))).build())
                .type(DialogType.multiAction(actions).columns(2).build()));
        player.showDialog(dialog);
    }

    private void remember(Player player, WikiEntry entry) {
        Deque<WikiEntry> history = recent.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>());
        history.removeIf(previous -> previous.title().equals(entry.title()));
        history.addFirst(entry);
        while (history.size() > 5) history.removeLast();
    }

    private String shortLabel(String title) {
        String readable = title.replace('_', ' ');
        return readable.length() <= 25 ? readable : readable.substring(0, 22) + "…";
    }

    private Component homeIntro() {
        return Component.text("KSIĘGA PRZEDMIOTÓW", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text("\n" + catalog.size() + " modeli  •  6 działów", NamedTextColor.AQUA))
                .append(Component.text("\nWyszukaj item lub wybierz ścieżkę poniżej.", NamedTextColor.GRAY));
    }

    private Component articleIntro(WikiArticleService.WikiArticle article, int page, int totalPages) {
        return Component.text("KARTA PRZEDMIOTU", NamedTextColor.AQUA, TextDecoration.BOLD)
                .append(Component.text("\n" + article.title(), NamedTextColor.GOLD))
                .append(Component.text("\nOpis z Wiki Laina.pl  •  " + page + "/" + totalPages, NamedTextColor.GRAY));
    }

    private Component articleBody(String text, List<WikiArticleService.WikiLink> links) {
        Component result = Component.empty();
        String[] lines = text.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) result = result.append(Component.newline());
            String line = lines[index];
            if (line.startsWith("• ")) {
                result = result.append(Component.text("• ", NamedTextColor.AQUA))
                        .append(linkedText(line.substring(2), links));
            } else if (isHeading(lines, index)) {
                result = result.append(Component.text(line, NamedTextColor.GOLD, TextDecoration.BOLD));
            } else {
                result = result.append(linkedText(line, links));
            }
        }
        return result;
    }

    private Component linkedText(String line, List<WikiArticleService.WikiLink> links) {
        Component result = Component.empty();
        int cursor = 0;
        while (cursor < line.length()) {
            int next = -1;
            WikiArticleService.WikiLink selected = null;
            for (var link : links) {
                int at = line.indexOf(link.label(), cursor);
                if (at >= 0 && (next < 0 || at < next || (at == next && link.label().length() > selected.label().length()))) {
                    next = at;
                    selected = link;
                }
            }
            if (selected == null) {
                result = result.append(Component.text(line.substring(cursor), NamedTextColor.WHITE));
                break;
            }
            if (next > cursor) result = result.append(Component.text(line.substring(cursor, next), NamedTextColor.WHITE));
            result = result.append(Component.text(selected.label(), NamedTextColor.AQUA, TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl(selected.url())));
            cursor = next + selected.label().length();
        }
        return result;
    }

    private boolean isHeading(String[] lines, int index) {
        String line = lines[index].strip();
        return !line.isEmpty() && line.length() < 45 && !line.endsWith(".") && !line.endsWith(":")
                && (index == 0 || lines[index - 1].isBlank())
                && index + 1 < lines.length && !lines[index + 1].isBlank();
    }

    static List<String> articlePages(String text, int maxLength) {
        if (maxLength < 1) throw new IllegalArgumentException("maxLength must be positive");
        if (text == null || text.isBlank()) return List.of("Brak opisu w artykule.");
        List<String> pages = new ArrayList<>();
        String remaining = text.strip();
        while (remaining.length() > maxLength) {
            int split = remaining.lastIndexOf('\n', maxLength);
            if (split < maxLength / 2) split = remaining.lastIndexOf(' ', maxLength);
            if (split < maxLength / 2) split = maxLength;
            pages.add(remaining.substring(0, split).strip());
            remaining = remaining.substring(split).strip();
        }
        if (!remaining.isBlank()) pages.add(remaining);
        return pages;
    }

    private String articleUrl(String title) {
        String base = plugin.getConfig().getString("wiki.public-url", "https://wiki.laina.pl/");
        return WikiUrls.article(base, title);
    }

}
