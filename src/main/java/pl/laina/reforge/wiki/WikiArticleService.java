package pl.laina.reforge.wiki;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WikiArticleService implements AutoCloseable {
    private static final Pattern INTERNAL_LINK = Pattern.compile("\\[\\[([^]\\[|]+)(?:\\|([^]\\[]+))?]](\\p{Ll}*)");
    private static final Pattern EXTERNAL_LINK = Pattern.compile("\\[(https?://[^\\s\\]|]+)[\\s|]+([^]]+)]");
    private static final Pattern HEADING = Pattern.compile("(?m)^(={2,6})[ \\t]*(.*?)[ \\t]*\\1[ \\t]*$");
    private static final Pattern LINK_MARKER = Pattern.compile("([\uE000\uE002])(\\d+)[\uE001\uE003]");
    private static final Pattern COMMENT = Pattern.compile("(?s)<!--.*?(?:-->|$)");
    private static final Pattern GALLERY = Pattern.compile("(?is)<gallery\\b[^>]*>(.*?)</gallery\\s*>");
    static final String GALLERY_NOTICE = "W tej sekcji znajduje się galeria. Aby ją zobaczyć, odwiedź pełny artykuł na stronie WWW.";
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp|ndash|mdash|hellip);", Pattern.CASE_INSENSITIVE);
    private final String endpoint;
    private final String publicBase;
    private final int maxCharacters;
    private final HttpClient client;
    private final AsyncCache<WikiArticle> cache;

    public WikiArticleService(LainaWikiPlugin plugin) {
        this(plugin.getConfig().getString("wiki.api-endpoint", "https://wiki.laina.pl/api.php"),
                plugin.getConfig().getString("wiki.public-url", "https://wiki.laina.pl/"),
                plugin.getConfig().getInt("wiki.max-article-characters", 7000),
                plugin.getConfig().getLong("wiki.article-cache-minutes", 30),
                plugin.getConfig().getInt("wiki.article-cache-capacity", 256));
    }

    WikiArticleService(String endpoint, String publicBase, int maxCharacters, long cacheMinutes, int capacity) {
        this.endpoint = endpoint;
        this.publicBase = publicBase;
        this.maxCharacters = Math.clamp(maxCharacters, 500, 50000);
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        long minutes = Math.clamp(cacheMinutes, 1L, 1440L);
        cache = new AsyncCache<>(this::fetchRemote, Duration.ofMinutes(minutes).toMillis(),
                Math.clamp(capacity, 1, 2000),
                System::currentTimeMillis);
    }

    public java.util.concurrent.CompletableFuture<AsyncCache.Result<WikiArticle>> load(String title) {
        return cache.load(title.strip().replace('_', ' '));
    }

    private WikiArticle fetchRemote(String requestedTitle) throws Exception {
        String query = "action=parse&prop=wikitext%7Clinks&format=xml&redirects=true&page="
                + URLEncoder.encode(requestedTitle, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "?" + query))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "LainaWiki/0.1 WikiDialog")
                .GET().build();
        HttpResponse<byte[]> response = client.send(request, WikiHttp.limitedBody());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Wiki HTTP " + response.statusCode());
        }

        var document = WikiHttp.parseXml(response.body());
        NodeList errors = document.getElementsByTagName("error");
        if (errors.getLength() > 0) {
            Element error = (Element) errors.item(0);
            if (error.getAttribute("code").equals("missingtitle")) throw new MissingArticleException(requestedTitle);
            throw new IllegalArgumentException(error.getAttribute("info"));
        }
        NodeList parses = document.getElementsByTagName("parse");
        if (parses.getLength() == 0) throw new IllegalStateException("Wiki nie zwrocila artykulu");
        Element parse = (Element) parses.item(0);
        String title = parse.getAttribute("title");
        NodeList wikiTexts = parse.getElementsByTagName("wikitext");
        String wikiText = withoutComments(wikiTexts.getLength() == 0 ? "" : wikiTexts.item(0).getTextContent());
        WikiRichText text = parseDialogText(wikiText, publicBase).truncate(maxCharacters,
                "\n\n…Dalsza część znajduje się na stronie Wiki.");
        Section acquisition = acquisitionSection(wikiText);
        WikiRichText acquisitionText = acquisition.end > acquisition.start
                ? parseDialogText(wikiText.substring(acquisition.start, acquisition.end), publicBase)
                    .truncate(maxCharacters, "\n\n…Pełny opis znajduje się na Wiki.")
                : new WikiRichText("", List.of());
        var missing = new java.util.HashSet<String>();
        NodeList linkedPages = parse.getElementsByTagName("pl");
        for (int i = 0; i < linkedPages.getLength(); i++) {
            Element linked = (Element) linkedPages.item(i);
            if (!linked.hasAttribute("exists")) missing.add(WikiCatalog.normalize(linked.getTextContent()));
        }
        WikiArticle article = new WikiArticle(title, text, extractLinks(wikiText, publicBase), acquisitionText, missing);
        return article;
    }

    @Override
    public void close() {
        cache.close();
        client.shutdownNow();
    }

    public static final class MissingArticleException extends Exception {
        MissingArticleException(String title) { super("Artykuł jeszcze nie istnieje: " + title); }
    }

    static boolean isMissingArticle(Throwable failure) {
        while (failure != null) {
            if (failure instanceof MissingArticleException) return true;
            failure = failure.getCause();
        }
        return false;
    }

    public record WikiArticle(String title, WikiRichText content, List<WikiLink> links, WikiRichText acquisition,
                              java.util.Set<String> missingTitles) {
        public WikiArticle { missingTitles = java.util.Set.copyOf(missingTitles); }
        public WikiArticle(String title, WikiRichText content, List<WikiLink> links, WikiRichText acquisition) {
            this(title, content, links, acquisition, java.util.Set.of());
        }
        public String text() { return content.text(); }
        public String acquisitionText() { return acquisition.text(); }
    }
    public record WikiLink(String label, String url, String targetTitle, boolean acquisition) {}
    private record LinkOccurrence(int start, int end, WikiLink link) {}
    private record Section(int start, int end) {}

    private static Section acquisitionSection(String text) {
        Matcher headings = HEADING.matcher(text);
        int start = -1;
        int depth = 0;
        while (headings.find()) {
            if (start >= 0 && headings.group(1).length() <= depth) return new Section(start, headings.start());
            String name = WikiCatalog.normalize(headings.group(2));
            if (start < 0 && List.of("jak zdobyc", "pozyskiwanie", "zdobywanie", "receptura", "crafting", "wytwarzanie").contains(name)) {
                start = headings.end();
                depth = headings.group(1).length();
            }
        }
        return start < 0 ? new Section(0, 0) : new Section(start, text.length());
    }

    static List<WikiLink> extractLinks(String wikiText, String publicBase) {
        return linkOccurrences(normalizeGalleries(withoutComments(wikiText)), publicBase).stream().map(LinkOccurrence::link).toList();
    }

    private static List<LinkOccurrence> linkOccurrences(String wikiText, String publicBase) {
        if (wikiText == null || wikiText.isBlank()) return List.of();
        List<LinkOccurrence> links = new ArrayList<>();
        Section acquisition = acquisitionSection(wikiText);
        Matcher internal = INTERNAL_LINK.matcher(wikiText);
        while (internal.find()) {
            String target = internal.group(1).strip();
            String namespace = target.toLowerCase(java.util.Locale.ROOT);
            if (namespace.startsWith("plik:") || namespace.startsWith("file:")
                    || namespace.startsWith("kategoria:") || namespace.startsWith("category:")) continue;
            String label = internal.group(2) == null ? target : internal.group(2).strip();
            label += internal.group(3);
            String page = target.split("#", 2)[0].strip();
            if (!label.isBlank() && !page.isBlank()) links.add(new LinkOccurrence(internal.start(), internal.end(),
                    new WikiLink(label, WikiUrls.article(publicBase, page), page,
                            internal.start() >= acquisition.start && internal.start() < acquisition.end)));
        }
        Matcher external = EXTERNAL_LINK.matcher(wikiText);
        while (external.find()) {
            String label = external.group(2).strip();
            if (!label.isBlank()) links.add(new LinkOccurrence(external.start(), external.end(),
                    new WikiLink(label, external.group(1), null,
                            external.start() >= acquisition.start && external.start() < acquisition.end)));
        }
        return List.copyOf(links);
    }

    static WikiRichText parseDialogText(String wikiText, String publicBase) {
        // Reserve private-use markers only during cleanup; none are sent to the client.
        String source = normalizeGalleries(withoutComments(wikiText)).replaceAll("[\uE000-\uE003]", "");
        List<LinkOccurrence> occurrences = linkOccurrences(source, publicBase).stream()
                .sorted(Comparator.comparingInt(LinkOccurrence::start)).toList();
        StringBuilder marked = new StringBuilder();
        int cursor = 0;
        for (int id = 0; id < occurrences.size(); id++) {
            LinkOccurrence occurrence = occurrences.get(id);
            if (occurrence.start() < cursor) continue;
            marked.append(source, cursor, occurrence.start());
            marked.append('\uE000').append(id).append('\uE001')
                    .append(occurrence.link().label()).append('\uE002').append(id).append('\uE003');
            cursor = occurrence.end();
        }
        marked.append(source, cursor, source.length());
        String cleaned = toDialogText(marked.toString());
        StringBuilder plain = new StringBuilder();
        List<WikiRichText.LinkSpan> spans = new ArrayList<>();
        var starts = new HashMap<Integer, Integer>();
        Matcher markers = LINK_MARKER.matcher(cleaned);
        cursor = 0;
        while (markers.find()) {
            plain.append(cleaned, cursor, markers.start());
            int id = Integer.parseInt(markers.group(2));
            if (markers.group(1).charAt(0) == '\uE000') {
                starts.put(id, plain.length());
            } else {
                Integer start = starts.remove(id);
                if (start != null && start < plain.length()) {
                    spans.add(new WikiRichText.LinkSpan(start, plain.length(), occurrences.get(id).link()));
                }
            }
            cursor = markers.end();
        }
        plain.append(cleaned, cursor, cleaned.length());
        String text = plain.toString();
        return new WikiRichText(text, markAuthorLinks(text, spans));
    }

    private static List<WikiRichText.LinkSpan> markAuthorLinks(String text, List<WikiRichText.LinkSpan> spans) {
        List<WikiRichText.LinkSpan> result = new ArrayList<>();
        Matcher headings = HEADING.matcher(text);
        boolean hasHeading = headings.find();
        int authorDepth = 0;
        for (var span : spans) {
            while (hasHeading && headings.start() <= span.start()) {
                int depth = headings.group(1).length();
                if (authorDepth > 0 && depth <= authorDepth) authorDepth = 0;
                String name = WikiCatalog.normalize(headings.group(2));
                if (name.equals("autorzy") || name.equals("autor")) authorDepth = depth;
                hasHeading = headings.find();
            }
            result.add(new WikiRichText.LinkSpan(span.start(), span.end(), span.link(), authorDepth > 0));
        }
        return result;
    }

    static String toPlainText(String wikiText) {
        return cleanText(wikiText, false);
    }

    static String toDialogText(String wikiText) {
        return cleanText(wikiText, true);
    }

    static int headingLevel(String line) {
        Matcher heading = HEADING.matcher(line.strip());
        return heading.matches() ? heading.group(1).length() : 0;
    }

    static String headingLabel(String line) {
        Matcher heading = HEADING.matcher(line.strip());
        return heading.matches() ? heading.group(2).strip() : line;
    }

    private static String cleanText(String wikiText, boolean keepHeadings) {
        String text = normalizeGalleries(withoutComments(wikiText));
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        text = text.replaceAll("(?i)__(?:NOTOC|TOC|FORCETOC|NOEDITSECTION|NOGALLERY|HIDDENCAT|INDEX|NOINDEX|STATICREDIRECT|NEWSECTIONLINK|NONEWSECTIONLINK|DISAMBIG)__", "");
        text = text.replaceAll("(?is)\\[\\[(?:Plik|File):[^]]+]]", "");
        text = text.replaceAll("(?is)\\[\\[(?:Kategoria|Category):[^]]+]]", "");
        text = EXTERNAL_LINK.matcher(text).replaceAll("$2");
        text = text.replaceAll("(?is)\\[\\[[^]|]+\\|([^]]+)]]", "$1");
        text = text.replaceAll("(?is)\\[\\[([^]]+)]]", "$1");
        text = text.replaceAll("(?i)<br\\s*/?>", "\n");
        text = text.replaceAll("(?is)</?(?:big|small|code|span|div|center|nowiki|u|b|i|em|strong|font|p)[^>]*>", "");
        if (keepHeadings) {
            // Some articles use standalone bold labels instead of MediaWiki headings.
            text = text.replaceAll("(?m)^[ \\t]*'''([^'\\n]+)'''[ \\t]*$", "== $1 ==");
            text = HEADING.matcher(text).replaceAll("\n\n$1 $2 $1\n\n");
        } else {
            text = HEADING.matcher(text).replaceAll("$2");
        }
        text = text.replace("'''", "").replace("''", "");
        text = text.replaceAll("(?is)\\{\\{.*?}}", "");
        text = text.replaceAll("(?m)^[ \\t]*\\*+[ \\t]*", "• ");
        text = text.replaceAll("(?m)[ \\t]+$", "");
        // Minecraft renders the base symbol, but emoji presentation selectors can show as missing glyphs.
        text = decodeEntities(text).replace("\uFE0E", "").replace("\uFE0F", "")
                .replaceAll("\\n{3,}", "\n\n").trim();
        return text.isBlank() ? "Brak opisu w artykule." : text;
    }

    private static String withoutComments(String text) {
        return COMMENT.matcher(text == null ? "" : text).replaceAll("");
    }

    private static String normalizeGalleries(String source) {
        return GALLERY.matcher(source).replaceAll(match -> {
            var links = new java.util.LinkedHashMap<String, String>();
            for (String line : match.group(1).split("\\R")) {
                int caption = line.indexOf('|');
                if (caption < 0) continue;
                String rest = line.substring(caption + 1);
                Matcher internal = INTERNAL_LINK.matcher(rest);
                while (internal.find()) {
                    String target = internal.group(1).strip();
                    if (target.contains(":")) continue;
                    String label = internal.group(2) == null ? target : internal.group(2).strip();
                    links.putIfAbsent(WikiCatalog.normalize(target), "* [[" + target + "|" + label + "]]");
                }
            }
            return Matcher.quoteReplacement("\n\n" + GALLERY_NOTICE + "\n\n" + String.join("\n", links.values()) + "\n\n");
        });
    }

    private static String decodeEntities(String text) {
        return ENTITY.matcher(text).replaceAll(match -> {
            String key = match.group(1).toLowerCase(java.util.Locale.ROOT);
            String decoded = switch (key) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos" -> "'";
                case "nbsp" -> " ";
                case "ndash" -> "–";
                case "mdash" -> "—";
                case "hellip" -> "…";
                default -> {
                    try {
                        int point = key.startsWith("#x") ? Integer.parseInt(key.substring(2), 16) : Integer.parseInt(key.substring(1));
                        // Do not allow encoded control/marker characters to enter our link-span stream.
                        yield Character.isValidCodePoint(point) && !Character.isISOControl(point)
                                && !(point >= 0xD800 && point <= 0xE003) ? new String(Character.toChars(point)) : match.group();
                    } catch (IllegalArgumentException invalid) { yield match.group(); }
                }
            };
            return Matcher.quoteReplacement(decoded);
        });
    }
}
