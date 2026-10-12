package pl.laina.reforge.wiki;

import java.util.Map;

/** Small, source-grounded response for optional companion integrations. */
final class WikiItemInsight {
    static Map<String, String> describe(WikiArticleService.WikiArticle article, boolean acquisition, boolean stale) {
        String source = acquisition ? article.acquisitionText() : overview(article.text());
        String summary = excerpt(source, 240);
        if (summary.isBlank()) summary = acquisition
                ? "Wiki nie podaje osobnej informacji o zdobywaniu. Sprawdź pełną kartę."
                : "Nie znalazłam krótkiego opisu. Sprawdź pełną kartę w Księdze.";
        return Map.of("status", "ok", "title", WikiCatalog.displayTitle(article.title()),
                "summary", summary, "stale", Boolean.toString(stale));
    }

    static String introduction(String text) {
        StringBuilder result = new StringBuilder();
        for (String line : text.split("\\R")) {
            if (WikiArticleService.headingLevel(line) > 0) break;
            result.append(line).append(' ');
        }
        return result.toString();
    }

    static String overview(String text) {
        StringBuilder result = new StringBuilder(introduction(text));
        boolean description = false;
        for (String line : text.split("\\R")) {
            if (WikiArticleService.headingLevel(line) > 0) {
                if (description) break;
                String heading = WikiCatalog.normalize(WikiArticleService.headingLabel(line));
                description = java.util.Set.of("opis", "dzialanie", "wlasciwosci", "efekty").contains(heading);
            } else if (description) result.append(line).append(' ');
        }
        return result.toString();
    }

    static String excerpt(String text, int limit) {
        String plain = text.replaceAll("[\\s\\p{Z}]+", " ").trim();
        if (plain.codePointCount(0, plain.length()) <= limit) return plain;
        int cut = plain.offsetByCodePoints(0, limit - 1);
        int word = plain.lastIndexOf(' ', cut);
        if (word > cut / 2) cut = word;
        return plain.substring(0, cut).stripTrailing() + "…";
    }
}
