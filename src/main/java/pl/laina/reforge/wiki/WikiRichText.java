package pl.laina.reforge.wiki;

import java.util.ArrayList;
import java.util.List;

/** Plain article text with link ranges anchored to actual source occurrences. */
public record WikiRichText(String text, List<LinkSpan> links, List<FormatSpan> formats) {
    public WikiRichText(String text, List<LinkSpan> links) { this(text, links, List.of()); }
    public WikiRichText {
        links = List.copyOf(links);
        formats = List.copyOf(formats);
    }
    public record FormatSpan(int start, int end, net.kyori.adventure.text.format.TextDecoration decoration) {}

    public record LinkSpan(int start, int end, WikiArticleService.WikiLink link, boolean author) {
        public LinkSpan(int start, int end, WikiArticleService.WikiLink link) {
            this(start, end, link, false);
        }
    }

    public WikiRichText slice(int start, int end) {
        List<LinkSpan> sliced = new ArrayList<>();
        for (LinkSpan span : links) {
            if (span.start() < end && span.end() > start) {
                sliced.add(new LinkSpan(Math.max(start, span.start()) - start,
                        Math.min(end, span.end()) - start, span.link(), span.author()));
            }
        }
        var styled = formats.stream().filter(span -> span.start() < end && span.end() > start)
                .map(span -> new FormatSpan(Math.max(start, span.start()) - start,
                        Math.min(end, span.end()) - start, span.decoration())).toList();
        return new WikiRichText(text.substring(start, end), sliced, styled);
    }

    WikiRichText truncate(int maxLength, String notice) {
        if (text.length() <= maxLength) return this;
        int end = text.substring(0, maxLength).stripTrailing().length();
        WikiRichText shortened = slice(0, end);
        return new WikiRichText(shortened.text() + notice, shortened.links(), shortened.formats());
    }
}
