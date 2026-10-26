package pl.laina.reforge.wiki;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.w3c.dom.Element;

/** Fetches the public article namespace and item category independently, following continuation tokens. */
final class WikiPageIndex {
    record Snapshot(List<String> articles, List<String> items) {}
    static Snapshot fetch(HttpClient client, String endpoint, BooleanSupplier closed) throws Exception {
        var articles = fetchPages(client, endpoint, false, closed);
        var items = fetchPages(client, endpoint, true, closed);
        if (articles.isEmpty() || items.isEmpty()) throw new IllegalStateException("Wiki zwróciła pusty indeks");
        return new Snapshot(articles, items);
    }
    private static List<String> fetchPages(HttpClient client, String endpoint, boolean items, BooleanSupplier closed) throws Exception {
        List<String> titles = new ArrayList<>();
        String continuation = null;
        String token = items ? "cmcontinue" : "apcontinue";
        String tag = items ? "cm" : "p";
        for (int batch = 0; batch < 40; batch++) {
            if (closed.getAsBoolean()) throw new InterruptedException("Wiki closed");
            String query = items ? "action=query&list=categorymembers&cmtitle="
                    + URLEncoder.encode("Kategoria:Przedmioty", StandardCharsets.UTF_8) + "&cmtype=page&cmlimit=500"
                    : "action=query&list=allpages&apnamespace=0&apfilterredir=nonredirects&aplimit=500";
            query += "&format=xml";
            if (continuation != null) query += "&" + token + "=" + URLEncoder.encode(continuation, StandardCharsets.UTF_8);
            var request = HttpRequest.newBuilder(URI.create(endpoint + "?" + query)).timeout(Duration.ofSeconds(12))
                    .header("User-Agent", "LainaWiki/0.2 ArticleIndex").GET().build();
            var response = client.send(request, WikiHttp.limitedBody());
            if (response.statusCode() != 200) throw new IllegalStateException("Wiki HTTP " + response.statusCode());
            var document = WikiHttp.parseXml(response.body());
            if (document.getElementsByTagName("error").getLength() > 0)
                throw new IllegalStateException("Wiki zwróciła błąd indeksu");
            var nodes = document.getElementsByTagName(tag);
            for (int index = 0; index < nodes.getLength(); index++) {
                var page = (Element) nodes.item(index);
                if (page.getAttribute("ns").equals("0") && !page.getAttribute("title").isBlank()) titles.add(page.getAttribute("title"));
            }
            var more = document.getElementsByTagName("continue");
            if (more.getLength() == 0) return List.copyOf(titles);
            String next = ((Element)more.item(0)).getAttribute(token);
            if (next.isBlank() || next.equals(continuation)) throw new IllegalStateException("Niepełny indeks Wiki: brak postępu kontynuacji");
            continuation = next;
        }
        throw new IllegalStateException("Przekroczono limit stron indeksu Wiki");
    }
}
