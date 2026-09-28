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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WikiArticleService implements AutoCloseable {
    private static final Pattern INTERNAL_LINK = Pattern.compile("\\[\\[([^]\\[|]+)(?:\\|([^]\\[]+))?]]");
    private static final Pattern EXTERNAL_LINK = Pattern.compile("\\[(https?://[^\\s]++)\\s+([^]]+)]]");
    private final LainaWikiPlugin plugin;
    private final HttpClient client;
    private final Map<String, CachedArticle> cache = new ConcurrentHashMap<>();

    public WikiArticleService(LainaWikiPlugin plugin) {
        this.plugin = plugin;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    public Optional<WikiArticle> cached(String title) {
        CachedArticle cached = cache.get(title);
        return cached != null && cached.validUntil().isAfter(Instant.now())
                ? Optional.of(cached.article()) : Optional.empty();
    }

    public WikiArticle fetch(String requestedTitle) throws Exception {
        Optional<WikiArticle> cached = cached(requestedTitle);
        if (cached.isPresent()) return cached.get();

        String endpoint = plugin.getConfig().getString("wiki.api-endpoint", "https://wiki.laina.pl/api.php");
        String query = "action=parse&prop=wikitext&format=xml&page="
                + URLEncoder.encode(requestedTitle, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "?" + query))
                .timeout(Duration.ofSeconds(10))
                .header("User-Agent", "LainaReforge/0.1 WikiDialog")
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
                : toPlainText(wikiText);
        int max = plugin.getConfig().getInt("wiki.max-article-characters", 7000);
        if (text.length() > max) text = text.substring(0, max).stripTrailing() + "\n\n…Dalsza część znajduje się na stronie Wiki.";
        String publicBase = plugin.getConfig().getString("wiki.public-url", "https://wiki.laina.pl/");
        WikiArticle article = new WikiArticle(title, text, extractLinks(wikiText, publicBase));
        long minutes = Math.max(1, plugin.getConfig().getLong("wiki.article-cache-minutes", 30));
        cache.put(requestedTitle, new CachedArticle(article, Instant.now().plus(Duration.ofMinutes(minutes))));
        return article;
    }

    @Override
    public void close() {
        cache.clear();
    }

    public record WikiArticle(String title, String text, List<WikiLink> links) {}
    public record WikiLink(String label, String url) {}
    private record CachedArticle(WikiArticle article, Instant validUntil) {}

    static List<WikiLink> extractLinks(String wikiText, String publicBase) {
        if (wikiText == null || wikiText.isBlank()) return List.of();
        List<WikiLink> links = new ArrayList<>();
        Matcher internal = INTERNAL_LINK.matcher(wikiText);
        while (internal.find()) {
            String target = internal.group(1).strip();
            String namespace = target.toLowerCase(java.util.Locale.ROOT);
            if (namespace.startsWith("plik:") || namespace.startsWith("file:")
                    || namespace.startsWith("kategoria:") || namespace.startsWith("category:")) continue;
            String label = internal.group(2) == null ? target : internal.group(2).strip();
            String page = target.split("#", 2)[0].strip();
            if (!label.isBlank() && !page.isBlank()) links.add(new WikiLink(label, WikiUrls.article(publicBase, page)));
        }
        Matcher external = EXTERNAL_LINK.matcher(wikiText);
        while (external.find()) {
            String label = external.group(2).strip();
            if (!label.isBlank()) links.add(new WikiLink(label, external.group(1)));
        }
        return List.copyOf(links);
    }

    static String toPlainText(String wikiText) {
        String text = wikiText == null ? "" : wikiText;
        text = text.replaceAll("(?is)\\[\\[(?:Plik|File):[^]]+]]", "");
        text = text.replaceAll("(?is)\\[\\[(?:Kategoria|Category):[^]]+]]", "");
        text = text.replaceAll("(?i)\\[https?://[^\\s]++\\s+([^]]+)]]", "$1");
        text = text.replaceAll("(?is)\\[\\[[^]|]+\\|([^]]+)]]", "$1");
        text = text.replaceAll("(?is)\\[\\[([^]]+)]]", "$1");
        text = text.replaceAll("(?i)<br\\s*/?>", "\n");
        text = text.replaceAll("(?is)</?(?:big|small|code|span|div|center|nowiki)[^>]*>", "");
        text = text.replaceAll("(?m)^\\s*=+\\s*(.*?)\\s*=+\\s*$", "$1");
        text = text.replace("'''", "").replace("''", "");
        text = text.replaceAll("(?is)\\{\\{.*?}}", "");
        text = text.replaceAll("(?m)^\\s*\\*\\s*", "• ");
        text = text.replaceAll("(?m)[ \\t]+$", "");
        text = text.replaceAll("\\n{3,}", "\n\n").trim();
        return text.isBlank() ? "Brak opisu w artykule." : text;
    }
}
