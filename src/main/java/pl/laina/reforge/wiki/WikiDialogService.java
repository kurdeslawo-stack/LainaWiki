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
import net.kyori.adventure.text.format.TextColor;
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
import java.util.function.Function;

public final class WikiDialogService implements org.bukkit.event.Listener {
    private static final int CONTENT_WIDTH = 360;
    // Native dialog items render at 16px; width/height only reserve layout space.
    private static final int ICON_SIZE = 16;
    private static final int PAGE_SIZE = 6;
    private static final int ARTICLE_PAGE_LENGTH = 1250;
    static final TextColor LINK_COLOR = TextColor.color(0xA8C7DC);

    private final LainaWikiPlugin plugin;
    private final WikiCatalog catalog;
    private final WikiArticleService articles;
    private final WikiIndexService index;
    private final WikiFavorites favorites;
    private final WikiPalette palette;
    private final Map<UUID, List<String>> favoriteTitles = new ConcurrentHashMap<>();
    private final Map<UUID, Deque<String>> trails = new ConcurrentHashMap<>();
    private record LoadedArticle(AsyncCache.Result<WikiArticleService.WikiArticle> article, List<String> favorites) {}
    private final Map<UUID, Deque<WikiEntry>> recent = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> requests = new ConcurrentHashMap<>();
    private final Map<UUID, Consumer<Player>> origins = new ConcurrentHashMap<>();

    @org.bukkit.event.EventHandler
    public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        requests.remove(event.getPlayer().getUniqueId());
        recent.remove(event.getPlayer().getUniqueId());
        favoriteTitles.remove(event.getPlayer().getUniqueId());
        trails.remove(event.getPlayer().getUniqueId());
        origins.remove(event.getPlayer().getUniqueId());
    }

    public WikiDialogService(LainaWikiPlugin plugin, WikiCatalog catalog,
                             WikiArticleService articles, WikiIndexService index, WikiFavorites favorites) {
        this.plugin = plugin;
        this.catalog = catalog;
        this.articles = articles;
        this.index = index;
        this.favorites = favorites;
        this.palette = new WikiPalette(plugin.getConfig(), plugin.getLogger());
    }

    public void showHome(Player player) {
        requests.remove(player.getUniqueId());
        trails.remove(player.getUniqueId());
        origins.remove(player.getUniqueId());
        ActionButton search = ActionButton.builder(Component.text("Szukaj przedmiotu", NamedTextColor.GOLD, TextDecoration.BOLD))
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
        actions.add(action("Przedmiot w ręce", "Otwórz kartę trzymanego przedmiotu", NamedTextColor.GREEN, this::openHeld));
        actions.add(action("Przeglądaj działy", "Broń, pancerze, narzędzia i inne", NamedTextColor.AQUA, this::showCategories));
        actions.add(action("Losowa karta", "Otwórz niespodziewaną kartę z księgi", NamedTextColor.YELLOW, this::openRandom));
        actions.add(action("★ Ulubione", "Twoja zapisana lista przedmiotów do zdobycia", NamedTextColor.GOLD,
                target -> loadFavorites(target, 0)));
        if (!recent.getOrDefault(player.getUniqueId(), new ArrayDeque<>()).isEmpty()) {
            actions.add(action("Ostatnio oglądane", "Wróć do niedawno otwartych kart", NamedTextColor.WHITE, this::showHistory));
        }
        actions.add(button("Pełna Wiki ↗", "Więcej informacji w przeglądarce",
                ClickEvent.openUrl(WikiUrls.home(plugin.getConfig().getString("wiki.public-url"))), NamedTextColor.AQUA));

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
        rememberOrigin(player, this::showCategories);
        List<ActionButton> actions = new ArrayList<>();
        for (WikiCategory category : WikiCategory.values()) {
            int count = catalog.entries(category).size();
            actions.add(ActionButton.builder(Component.text("• ", category.color())
                            .append(Component.text(category.label(), NamedTextColor.WHITE))
                            .append(Component.text("  ·  " + count, NamedTextColor.GRAY)))
                    .width(170)
                    .action(click(target -> showCategory(target, category, 0))).build());
        }
        int wikiOnly = index.unmapped(catalog).size();
        if (wikiOnly > 0) {
            actions.add(action("Strony z Wiki  ·  " + wikiOnly,
                    "Artykuły, które nie mają jeszcze pewnego ID itemu", NamedTextColor.AQUA,
                    target -> showWikiPages(target, 0)));
        }
        actions.add(action("← Do księgi", "Wróć na stronę główną", NamedTextColor.GOLD, this::showHome));
        showMenu(player, "Ścieżki księgi", Component.text("WYBIERZ DZIAŁ", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text("\n" + catalog.size() + " przedmiotów  •  6 działów", NamedTextColor.AQUA)), actions);
    }

    private void showWikiPages(Player player, int requestedPage) {
        rememberOrigin(player, target -> showWikiPages(target, requestedPage));
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
                    .width(170)
                    .action(click(target -> openWikiTitle(target, title))).build());
        }
        actions.add(action("← Wszystkie działy", "Wróć do działów", NamedTextColor.GRAY, this::showCategories));
        if (page < lastPage) actions.add(action("Następna →", "Następna strona", NamedTextColor.GOLD,
                target -> showWikiPages(target, page + 1)));
        if (page > 0) actions.add(action("← Poprzednia", "Poprzednia strona", NamedTextColor.GRAY,
                target -> showWikiPages(target, page - 1)));
        Component intro = Component.text("ODKRYTE NA WIKI", NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text("\n" + titles.size() + " stron  •  strona " + (page + 1)
                        + " z " + (lastPage + 1), NamedTextColor.GRAY));
        showMenu(player, "Strony z Wiki", intro, actions);
    }

    private void openWikiTitle(Player player, String title) {
        WikiEntry entry = catalog.exact(title).orElseGet(() -> WikiEntry.wikiOnly(title));
        openEntry(player, entry, entry.createIcon());
    }

    private void navigate(Player player, String from, String title) {
        if (!WikiFavorites.sameTitle(from, title)) {
            Deque<String> trail = trails.computeIfAbsent(player.getUniqueId(), ignored -> new ArrayDeque<>());
            trail.addFirst(from);
            while (trail.size() > 20) trail.removeLast();
        }
        openWikiTitle(player, title);
    }

    private <T> void storageAction(Player player, java.util.concurrent.CompletableFuture<T> operation, Consumer<T> success) {
        UUID id = player.getUniqueId();
        UUID request = UUID.randomUUID();
        requests.put(id, request);
        showLoading(player, request);
        operation.whenComplete((value, failure) -> {
            if (!plugin.isEnabled()) return;
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline() || !requests.remove(id, request)) return;
                    if (failure == null) success.accept(value);
                    else {
                        plugin.getLogger().warning("Ulubione: " + failure);
                        showError(player, "Ulubione chwilowo niedostępne", "Nie udało się odczytać lub zapisać listy. Spróbuj ponownie.");
                    }
                });
            } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) { }
        });
    }

    private void loadFavorites(Player player, int page) {
        storageAction(player, favorites.list(player.getUniqueId()), titles -> {
            favoriteTitles.put(player.getUniqueId(), titles);
            showFavorites(player, titles, page);
        });
    }

    private void showFavorites(Player player, List<String> titles, int requestedPage) {
        rememberOrigin(player, target -> loadFavorites(target, requestedPage));
        int lastPage = Math.max(0, (titles.size() - 1) / PAGE_SIZE);
        int page = Math.clamp(requestedPage, 0, lastPage);
        List<ActionButton> actions = new ArrayList<>();
        titles.stream().skip((long) page * PAGE_SIZE).limit(PAGE_SIZE).forEach(title ->
                actions.add(action(shortLabel(title), title, target -> openWikiTitle(target, title))));
        if (page > 0) actions.add(action("← Poprzednia", "Poprzednia strona", target -> loadFavorites(target, page - 1)));
        if (page < lastPage) actions.add(action("Następna →", "Następna strona", target -> loadFavorites(target, page + 1)));
        actions.add(action("← Do księgi", "Wróć do wyszukiwarki", NamedTextColor.GRAY, this::showHome));
        showMenu(player, "★ Ulubione", Component.text(titles.isEmpty()
                ? "Twoja lista jest pusta. Otwórz kartę i wybierz ★ Dodaj do ulubionych."
                : titles.size() + "/" + WikiFavorites.LIMIT + " zapisanych kart • strona " + (page + 1) + "/" + (lastPage + 1),
                NamedTextColor.GOLD), actions);
    }

    public void showCategory(Player player, WikiCategory category, int requestedPage) {
        rememberOrigin(player, target -> showCategory(target, category, requestedPage));
        List<WikiEntry> entries = catalog.entries(category);
        int lastPage = Math.max(0, (entries.size() - 1) / PAGE_SIZE);
        int page = Math.clamp(requestedPage, 0, lastPage);
        List<ActionButton> actions = new ArrayList<>();
        int start = page * PAGE_SIZE;
        for (int index = start; index < Math.min(start + PAGE_SIZE, entries.size()); index++) {
            WikiEntry entry = entries.get(index);
            actions.add(ActionButton.builder(Component.text(shortLabel(entry.title()), NamedTextColor.WHITE))
                    .width(170)
                    .action(click(target -> openEntry(target, entry, entry.createIcon()))).build());
        }
        actions.add(action("← Wszystkie działy", "Wróć do działów", NamedTextColor.GRAY, this::showCategories));
        if (page < lastPage) actions.add(action("Następna →", "Następna strona", NamedTextColor.GOLD,
                target -> showCategory(target, category, page + 1)));
        if (page > 0) actions.add(action("← Poprzednia", "Poprzednia strona", NamedTextColor.GRAY,
                target -> showCategory(target, category, page - 1)));
        Component intro = Component.text(category.label().toUpperCase(), NamedTextColor.GOLD, TextDecoration.BOLD)
                .append(Component.text("\n" + entries.size() + " kart  •  strona " + (page + 1) + " z " + (lastPage + 1), NamedTextColor.GRAY));
        showMenu(player, category.label(), intro, actions);
    }

    public void showHistory(Player player) {
        rememberOrigin(player, this::showHistory);
        Deque<WikiEntry> history = recent.get(player.getUniqueId());
        if (history == null || history.isEmpty()) {
            showHome(player);
            return;
        }
        List<ActionButton> actions = new ArrayList<>();
        for (WikiEntry entry : history) {
            actions.add(ActionButton.builder(Component.text(shortLabel(entry.title()), NamedTextColor.WHITE))
                    .width(170)
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
        if (query.length() > 128) {
            showError(player, "Za długa nazwa", "Wpisz maksymalnie 128 znaków.");
            return;
        }
        rememberOrigin(player, this::showHome);
        List<String> titles = new ArrayList<>(catalog.titles());
        titles.addAll(index.titles());
        WikiSearch.Results results = WikiSearch.find(query, titles);
        if (!results.approximate() && !results.titles().isEmpty()
                && (results.titles().size() == 1 || WikiCatalog.normalize(results.titles().getFirst()).equals(WikiCatalog.normalize(query)))) {
            openWikiTitle(player, results.titles().getFirst());
        } else showSearchResults(player, query.strip(), results, 0);
    }

    private void showSearchResults(Player player, String query, WikiSearch.Results results, int requestedPage) {
        int lastPage = Math.max(0, (results.titles().size() - 1) / PAGE_SIZE);
        int page = Math.clamp(requestedPage, 0, lastPage);
        rememberOrigin(player, target -> showSearchResults(target, query, results, page));
        List<ActionButton> actions = new ArrayList<>();
        results.titles().stream().skip((long) page * PAGE_SIZE).limit(PAGE_SIZE).forEach(title ->
                actions.add(action(shortLabel(title), title, NamedTextColor.WHITE, target -> openWikiTitle(target, title))));
        if (page > 0) actions.add(action("← Poprzednia", "", NamedTextColor.WHITE,
                target -> showSearchResults(target, query, results, page - 1)));
        if (page < lastPage) actions.add(action("Następna →", "", NamedTextColor.GOLD,
                target -> showSearchResults(target, query, results, page + 1)));
        actions.add(action("← Nowe wyszukiwanie", "", NamedTextColor.GOLD, this::showHome));
        actions.add(button("Szukaj na całej Wiki ↗", "", ClickEvent.openUrl(
                WikiUrls.search(plugin.getConfig().getString("wiki.public-url"), query)), NamedTextColor.AQUA));
        String summary = results.titles().isEmpty() ? "Brak wyniku w księdze. Sprawdź pisownię lub całą Wiki."
                : (results.approximate() ? "Czy chodziło Ci o…?" : results.titles().size() + " pasujących kart")
                    + " • strona " + (page + 1) + "/" + (lastPage + 1);
        showMenu(player, "Wyniki wyszukiwania", Component.text(query, NamedTextColor.GOLD)
                .append(Component.text("\n" + summary, NamedTextColor.WHITE)), actions);
    }

    private void rememberOrigin(Player player, Consumer<Player> origin) {
        origins.put(player.getUniqueId(), origin);
        trails.remove(player.getUniqueId());
    }

    private void backToList(Player player) {
        origins.getOrDefault(player.getUniqueId(), this::showHome).accept(player);
    }

    private void showLoading(Player player, UUID request) {
        UUID id = player.getUniqueId();
        ActionButton cancel = ActionButton.builder(Component.text("Zamknij", NamedTextColor.WHITE))
                .action(DialogAction.customClick((response, audience) -> {
                    if (audience instanceof Player target && target.getUniqueId().equals(id)) requests.remove(id, request);
                }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build())).build();
        player.showDialog(Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Component.text("Ładowanie…", NamedTextColor.AQUA))
                        .canCloseWithEscape(true).pause(false).afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(List.of(DialogBody.plainMessage(Component.text("Przetwarzam dane. Możesz zamknąć okno klawiszem Esc."), CONTENT_WIDTH)))
                        .build())
                .type(DialogType.notice(cancel))));
    }

    private void openEntry(Player player, WikiEntry entry, ItemStack icon) {
        UUID request = UUID.randomUUID();
        UUID playerId = player.getUniqueId();
        requests.put(playerId, request);
        showLoading(player, request);
        player.sendActionBar(Component.text("Ładowanie artykułu „" + entry.title() + "”…", NamedTextColor.AQUA));
        var saved = favorites.list(playerId).exceptionally(failure -> {
            plugin.getLogger().warning("Odczyt ulubionych: " + failure);
            return List.of();
        });
        articles.load(entry.title()).thenCombine(saved, LoadedArticle::new).whenComplete((loaded, failure) -> {
            if (!plugin.isEnabled()) return;
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline() || !requests.remove(playerId, request)) return;
                    if (failure == null) {
                        var result = loaded.article();
                        favoriteTitles.put(playerId, loaded.favorites());
                        showArticle(player, entry, icon, result.value(), 0);
                        if (result.stale()) player.sendMessage(Component.text(
                                "Wiki jest niedostępna. Pokazuję wcześniej zapisaną wersję opisu.", NamedTextColor.YELLOW));
                    } else {
                        plugin.getLogger().warning("Wiki: " + entry.title() + ": " + failure);
                        if (WikiArticleService.isMissingArticle(failure)) {
                            showError(player, "Artykuł jeszcze nie istnieje",
                                    "Na Wiki nie ma jeszcze strony „" + entry.title() + "”. To nie jest błąd połączenia.");
                        } else showError(player, "Nie udało się pobrać artykułu",
                                "Wiki chwilowo nie odpowiada lub jest zbyt wiele zapytań. Spróbuj ponownie za chwilę.");
                    }
                });
            } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
                // The server disabled this plugin while the HTTP request was completing.
            }
        });
    }

    private void showArticle(Player player, WikiEntry entry, ItemStack icon,
                             WikiArticleService.WikiArticle article, int requestedPage) {
        showArticle(player, entry, icon, article, requestedPage, false);
    }

    private void showArticle(Player player, WikiEntry entry, ItemStack icon,
                             WikiArticleService.WikiArticle article, int requestedPage, boolean acquisitionOnly) {
        requests.remove(player.getUniqueId());
        remember(player, entry);
        List<WikiRichText> pages = articleRichPages(acquisitionOnly ? article.acquisition() : article.content(), ARTICLE_PAGE_LENGTH);
        int page = Math.clamp(requestedPage, 0, pages.size() - 1);
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.item(WikiIconPresentation.titleOnly(icon, article.title())).showDecorations(false).showTooltip(true)
                .width(ICON_SIZE).height(ICON_SIZE)
                .description(DialogBody.plainMessage(articleIntro(page + 1, pages.size()), CONTENT_WIDTH - ICON_SIZE - 2))
                .build());
        if (acquisitionOnly) body.add(DialogBody.plainMessage(Component.text("JAK ZDOBYĆ? • według Wiki", NamedTextColor.GOLD), CONTENT_WIDTH));
        for (WikiRichText block : richParts(pages.get(page), articleBlocks(pages.get(page).text()))) {
            Component contents = articleBody(block, article.title(), article.missingTitles());
            // Native dialog item bodies reject AIR; text blocks must remain plain messages.
            body.add(DialogBody.plainMessage(contents, CONTENT_WIDTH));
        }
        String url = articleUrl(article.title());
        ActionButton back = action("Wróć", "Wróć do listy", NamedTextColor.WHITE, this::backToList);
        ActionButton web = button("Pełny artykuł", url, ClickEvent.openUrl(url), NamedTextColor.AQUA);
        List<ActionButton> actions = new ArrayList<>();
        if (page > 0) actions.add(action("← Poprzednia", "Poprzednia strona opisu", NamedTextColor.WHITE,
                target -> showArticle(target, entry, icon, article, page - 1, acquisitionOnly)));
        if (page < pages.size() - 1) actions.add(action("Czytaj dalej →", "Następna strona opisu", NamedTextColor.GOLD,
                target -> showArticle(target, entry, icon, article, page + 1, acquisitionOnly)));
        boolean favorite = favoriteTitles.getOrDefault(player.getUniqueId(), List.of()).stream()
                .anyMatch(title -> WikiFavorites.sameTitle(title, article.title()));
        actions.add(action(favorite ? "★ Usuń z ulubionych" : "★ Dodaj do ulubionych", "Zapisz przedmiot na swojej liście celów", NamedTextColor.GOLD,
                target -> storageAction(target, favorites.toggle(target.getUniqueId(), article.title()), change -> {
                    if (change == WikiFavorites.Change.LIMIT_REACHED) target.sendMessage(Component.text(
                            "Lista mieści maksymalnie " + WikiFavorites.LIMIT + " kart. Usuń jedną, aby dodać kolejną.", NamedTextColor.YELLOW));
                    storageAction(target, favorites.list(target.getUniqueId()), titles -> {
                        favoriteTitles.put(target.getUniqueId(), titles);
                        showArticle(target, entry, icon, article, page, acquisitionOnly);
                    });
                })));
        if (!article.acquisitionText().isBlank()) actions.add(action(acquisitionOnly ? "← Pełny opis" : "Jak zdobyć?",
                "Informacje o zdobywaniu podane w artykule", acquisitionOnly ? NamedTextColor.WHITE : NamedTextColor.GREEN,
                target -> showArticle(target, entry, icon, article, 0, !acquisitionOnly)));
        if (!article.links().isEmpty()) actions.add(action("Powiązane artykuły", "Przejdź do źródeł, bossów i składników wymienionych na Wiki",
                NamedTextColor.YELLOW,
                target -> showRelations(target, entry, icon, article, acquisitionOnly, 0)));
        if (!trails.getOrDefault(player.getUniqueId(), new ArrayDeque<>()).isEmpty()) actions.add(action("← Poprzednia karta",
                "Wróć do artykułu, z którego tu przyszedłeś", NamedTextColor.WHITE, target -> {
                    Deque<String> trail = trails.get(target.getUniqueId());
                    if (trail != null && !trail.isEmpty()) openWikiTitle(target, trail.removeFirst());
                    else showHome(target);
                }));
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

    private void showRelations(Player player, WikiEntry entry, ItemStack icon, WikiArticleService.WikiArticle article,
                               boolean acquisitionOnly, int requestedPage) {
        Map<String, WikiArticleService.WikiLink> distinct = new java.util.LinkedHashMap<>();
        article.links().stream().filter(link -> !acquisitionOnly || link.acquisition())
                .forEach(link -> distinct.putIfAbsent(link.url(), link));
        List<WikiArticleService.WikiLink> links = List.copyOf(distinct.values());
        int lastPage = Math.max(0, (links.size() - 1) / PAGE_SIZE);
        int page = Math.clamp(requestedPage, 0, lastPage);
        List<ActionButton> actions = new ArrayList<>();
        links.stream().skip((long) page * PAGE_SIZE).limit(PAGE_SIZE).forEach(link -> {
            if (link.targetTitle() != null) actions.add(action(shortLabel(link.targetTitle()), link.label(),
                    article.missingTitles().contains(WikiCatalog.normalize(link.targetTitle())) ? NamedTextColor.RED : NamedTextColor.AQUA,
                    target -> navigate(target, article.title(), link.targetTitle())));
            else actions.add(button(shortLabel(link.label()) + " ↗", "Otwórz stronę: " + link.url(),
                    ClickEvent.openUrl(link.url()), NamedTextColor.AQUA));
        });
        if (page > 0) actions.add(action("← Poprzednia", "Poprzednia strona", target -> showRelations(target, entry, icon, article, acquisitionOnly, page - 1)));
        if (page < lastPage) actions.add(action("Następna →", "Następna strona", target -> showRelations(target, entry, icon, article, acquisitionOnly, page + 1)));
        actions.add(action("← Do opisu", "Wróć do karty", target -> showArticle(target, entry, icon, article, 0, acquisitionOnly)));
        showMenu(player, "Powiązania z Wiki", Component.text(article.title() + "\n"
                + (links.isEmpty() ? "Wiki nie podaje tu linków do innych stron." : links.size() + " odnośników • strona " + (page + 1) + "/" + (lastPage + 1)),
                NamedTextColor.GOLD), actions);
    }

    private void showError(Player player, String title, String message) {
        requests.remove(player.getUniqueId());
        ActionButton back = action("Wróć", "Wróć do listy", NamedTextColor.WHITE, this::backToList);
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
        return button(label, tooltip, click, NamedTextColor.WHITE);
    }

    private ActionButton button(String label, String tooltip, ClickEvent<?> click, NamedTextColor color) {
        return ActionButton.builder(Component.text(label, buttonColor(color)))
                .width(170)
                .action(DialogAction.staticAction(click)).build();
    }

    private ActionButton action(String label, String tooltip, Consumer<Player> handler) {
        return action(label, tooltip, NamedTextColor.AQUA, handler);
    }

    private ActionButton action(String label, String tooltip, NamedTextColor color, Consumer<Player> handler) {
        return ActionButton.builder(Component.text(label, buttonColor(color)))
                .width(170).action(click(handler)).build();
    }

    static NamedTextColor interactiveColor(NamedTextColor color) {
        // Allow a few home accents; gray/red remain reserved for status messages.
        if (color == NamedTextColor.GOLD || color == NamedTextColor.RED || color == NamedTextColor.DARK_RED) {
            return NamedTextColor.GOLD;
        }
        if (color == NamedTextColor.WHITE || color == NamedTextColor.GREEN || color == NamedTextColor.YELLOW) {
            return color;
        }
        return NamedTextColor.AQUA;
    }

    private TextColor buttonColor(NamedTextColor color) {
        if (color == NamedTextColor.RED || color == NamedTextColor.DARK_RED) return palette.color("missing-link", NamedTextColor.RED);
        NamedTextColor normalized = interactiveColor(color);
        String key = normalized == NamedTextColor.RED ? "missing-link"
                : normalized == NamedTextColor.GOLD ? "button-highlight"
                : normalized == NamedTextColor.GREEN ? "button-positive"
                : normalized == NamedTextColor.YELLOW ? "button-secondary"
                : normalized == NamedTextColor.WHITE ? "button-neutral" : "button";
        return palette.color(key, normalized);
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
        requests.remove(player.getUniqueId());
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
                .append(Component.text("\n" + catalog.size() + " przedmiotów  •  6 działów", NamedTextColor.AQUA))
                .append(Component.text("\nWyszukaj item lub wybierz ścieżkę poniżej.", NamedTextColor.GRAY));
    }

    private Component articleIntro(int page, int totalPages) {
        return Component.text("KARTA PRZEDMIOTU", palette.color("accent", NamedTextColor.AQUA), TextDecoration.BOLD)
                .append(Component.text("\nOpis z Wiki Laina.pl  •  " + page + "/" + totalPages, NamedTextColor.GRAY));
    }

    static List<String> articleBlocks(String text) {
        List<String> blocks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean list = false;
        for (String line : text.split("\n", -1)) {
            boolean nextList = line.startsWith("• ");
            if (line.isBlank() || (!current.isEmpty() && nextList != list)) {
                if (!current.isEmpty()) {
                    blocks.add(current.toString());
                    current.setLength(0);
                }
                if (line.isBlank()) continue;
            }
            if (!current.isEmpty()) current.append('\n');
            current.append(line);
            list = nextList;
        }
        if (!current.isEmpty()) blocks.add(current.toString());
        return blocks;
    }

    private Component articleBody(WikiRichText text, String sourceTitle, java.util.Set<String> missing) {
        Component result = Component.empty();
        String[] lines = text.text().split("\n", -1);
        int offset = 0;
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) result = result.append(Component.newline());
            String line = lines[index];
            if (line.startsWith("• ")) {
                result = result.append(Component.text("• ", palette.color("accent", NamedTextColor.AQUA)))
                        .append(linkedText(text.slice(offset + 2, offset + line.length()), sourceTitle, missing));
            } else if (WikiArticleService.headingLevel(line) > 0) {
                result = result.append(Component.text(WikiArticleService.headingLabel(line),
                        palette.color("heading", NamedTextColor.GOLD),
                        TextDecoration.BOLD));
            } else {
                result = result.append(linkedText(text.slice(offset, offset + line.length()), sourceTitle, missing));
            }
            offset += line.length() + 1;
        }
        return result;
    }

    private Component linkedText(WikiRichText text, String sourceTitle, java.util.Set<String> missing) {
        return linkedText(text, link -> link.targetTitle() == null ? ClickEvent.openUrl(link.url())
                : internalLink(sourceTitle, link.targetTitle()), missing, palette);
    }

    static Component linkedText(WikiRichText text, Function<WikiArticleService.WikiLink, ClickEvent> action) {
        return linkedText(text, action, java.util.Set.of(), WikiPalette.defaults());
    }

    static Component linkedText(WikiRichText text, Function<WikiArticleService.WikiLink, ClickEvent> action,
                                java.util.Set<String> missing, WikiPalette palette) {
        Component result = Component.empty();
        int cursor = 0;
        for (var span : text.links()) {
            if (span.start() > cursor) result = result.append(Component.text(text.text().substring(cursor, span.start()), palette.color("text", NamedTextColor.WHITE)));
            boolean absent = span.link().targetTitle() != null && missing.contains(WikiCatalog.normalize(span.link().targetTitle()));
            result = result.append(Component.text(text.text().substring(span.start(), span.end()),
                            absent ? palette.color("missing-link", NamedTextColor.RED)
                                    : palette.color(span.author() ? "author-link" : "link", span.author() ? NamedTextColor.AQUA : LINK_COLOR), TextDecoration.UNDERLINED)
                    .clickEvent(action.apply(span.link()))
                    .hoverEvent(Component.text(absent ? "Ten artykuł jeszcze nie istnieje" : "Otwórz: " + span.link().label())));
            cursor = span.end();
        }
        if (cursor < text.text().length()) result = result.append(Component.text(text.text().substring(cursor), palette.color("text", NamedTextColor.WHITE)));
        return result;
    }

    private ClickEvent internalLink(String sourceTitle, String targetTitle) {
        DialogAction.CustomClickAction action = DialogAction.customClick((response, audience) -> {
            if (audience instanceof Player target && plugin.isEnabled()) {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (target.isOnline()) navigate(target, sourceTitle, targetTitle);
                });
            }
        }, ClickCallback.Options.builder().uses(ClickCallback.UNLIMITED_USES).lifetime(Duration.ofMinutes(30)).build());
        return ClickEvent.custom(action.id(), action.additions());
    }

    static List<WikiRichText> articleRichPages(WikiRichText text, int maxLength) {
        if (text.text().isBlank()) text = new WikiRichText("Brak opisu w artykule.", List.of());
        return richParts(text, articlePages(text.text(), maxLength));
    }

    static List<WikiRichText> richParts(WikiRichText source, List<String> parts) {
        List<WikiRichText> result = new ArrayList<>();
        int cursor = 0;
        for (String part : parts) {
            int start = source.text().indexOf(part, cursor);
            if (start < 0) throw new IllegalArgumentException("Article part does not belong to its source");
            int end = start + part.length();
            result.add(source.slice(start, end));
            cursor = end;
        }
        return result;
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
            // Keep a section title with its content instead of orphaning it at the page bottom.
            String candidate = remaining.substring(0, split).stripTrailing();
            int lastLine = candidate.lastIndexOf('\n') + 1;
            if (lastLine > 0 && WikiArticleService.headingLevel(candidate.substring(lastLine)) > 0) {
                split = lastLine;
            }
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
