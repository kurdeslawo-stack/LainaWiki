package pl.laina.reforge.wiki;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WikiArticleService implements AutoCloseable {
    private static final Pattern INTERNAL_LINK = Pattern.compile("\\[\\[([^]\\[|]+)(?:\\|([^]\\[]+))?]](\\p{Ll}*)");
    private static final Pattern EXTERNAL_LINK = Pattern.compile("\\[(https?://[^\\s\\]]+)\\s+([^]]+)]");
    private static final Pattern HEADING = Pattern.compile("(?m)^(={2,6})[ \\t]*(.*?)[ \\t]*\\1[ \\t]*$");
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
        String query = "action=parse&prop=wikitext&format=xml&page="
                + URLEncoder.encode(requestedTitle, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "?" + query))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "LainaWiki/0.1 WikiDialog")
                .GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Wiki HTTP " + response.statusCode());
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(response.body()));
        NodeList errors = document.getElementsByTagName("error");
        if (errors.getLength() > 0) {
            Element error = (Element) errors.item(0);
            throw new IllegalArgumentException(error.getAttribute("info"));
        }
        NodeList parses = document.getElementsByTagName("parse");
        if (parses.getLength() == 0) throw new IllegalStateException("Wiki nie zwrocila artykulu");
        Element parse = (Element) parses.item(0);
        String title = parse.getAttribute("title");
        NodeList wikiTexts = parse.getElementsByTagName("wikitext");
        String wikiText = wikiTexts.getLength() == 0 ? "" : wikiTexts.item(0).getTextContent();
        String text = wikiTexts.getLength() == 0
                ? "Brak opisu w artykule."
                : toDialogText(wikiText);
        int max = maxCharacters;
        if (text.length() > max) text = text.substring(0, max).stripTrailing() + "\n\n…Dalsza część znajduje się na stronie Wiki.";
        Section acquisition = acquisitionSection(wikiText);
        String acquisitionText = acquisition.end > acquisition.start
                ? toDialogText(wikiText.substring(acquisition.start, acquisition.end)) : "";
        if (acquisitionText.length() > max) acquisitionText = acquisitionText.substring(0, max)
                + "\n\n…Pełny opis znajduje się na Wiki.";
        WikiArticle article = new WikiArticle(title, text, extractLinks(wikiText, publicBase), acquisitionText);
        return article;
    }

    @Override
    public void close() {
        cache.close();
        client.shutdownNow();
    }

    public record WikiArticle(String title, String text, List<WikiLink> links, String acquisitionText) {}
    public record WikiLink(String label, String url, String targetTitle, boolean acquisition) {}
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
        if (wikiText == null || wikiText.isBlank()) return List.of();
        List<WikiLink> links = new ArrayList<>();
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
            if (!label.isBlank() && !page.isBlank()) links.add(new WikiLink(label, WikiUrls.article(publicBase, page), page,
                    internal.start() >= acquisition.start && internal.start() < acquisition.end));
        }
        Matcher external = EXTERNAL_LINK.matcher(wikiText);
        while (external.find()) {
            String label = external.group(2).strip();
            if (!label.isBlank()) links.add(new WikiLink(label, external.group(1), null,
                    external.start() >= acquisition.start && external.start() < acquisition.end));
        }
        return List.copyOf(links);
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
        String text = wikiText == null ? "" : wikiText;
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        text = text.replaceAll("(?i)__(?:NOTOC|TOC|FORCETOC|NOEDITSECTION|NOGALLERY|HIDDENCAT|INDEX|NOINDEX|STATICREDIRECT|NEWSECTIONLINK|NONEWSECTIONLINK|DISAMBIG)__", "");
        text = text.replaceAll("(?is)\\[\\[(?:Plik|File):[^]]+]]", "");
        text = text.replaceAll("(?is)\\[\\[(?:Kategoria|Category):[^]]+]]", "");
        text = EXTERNAL_LINK.matcher(text).replaceAll("$2");
        text = text.replaceAll("(?is)\\[\\[[^]|]+\\|([^]]+)]]", "$1");
        text = text.replaceAll("(?is)\\[\\[([^]]+)]]", "$1");
        text = text.replaceAll("(?i)<br\\s*/?>", "\n");
        text = text.replaceAll("(?is)</?(?:big|small|code|span|div|center|nowiki)[^>]*>", "");
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
        text = text.replaceAll("\\n{3,}", "\n\n").trim();
        return text.isBlank() ? "Brak opisu w artykule." : text;
    }
}
