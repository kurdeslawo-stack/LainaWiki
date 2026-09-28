package pl.laina.reforge.wiki;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

final class WikiUrls {
    private WikiUrls() {}

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
