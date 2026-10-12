package pl.laina.reforge.wiki;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class WikiUrls {
    private WikiUrls() {}

    static String home(String configuredBase) {
        String base = configuredBase == null || configuredBase.isBlank() ? "https://wiki.laina.pl/" : configuredBase.strip();
        return base.replace("index.php?title={title}", "").replace("index.php?title=", "").replace("{title}", "");
    }

    static String search(String configuredBase, String query) {
        String url = article(configuredBase, "Special:Search");
        return url + (url.contains("?") ? "&" : "?") + "search=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
    }

    static String article(String configuredBase, String title) {
        String base = configuredBase == null || configuredBase.isBlank()
                ? "https://wiki.laina.pl/" : configuredBase.strip();
        String encoded = URLEncoder.encode(title.replace(' ', '_'), StandardCharsets.UTF_8)
                .replace("+", "%20");
        if (base.contains("{title}")) return base.replace("{title}", encoded);
        if (base.endsWith("index.php?title=")) return base + encoded;
        return base.replaceAll("/+$", "") + "/index.php?title=" + encoded;
    }
}
