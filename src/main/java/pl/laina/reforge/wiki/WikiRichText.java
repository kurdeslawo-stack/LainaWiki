package pl.laina.reforge.wiki;

import java.util.ArrayList;
import java.util.List;

/** Plain article text with link ranges anchored to actual source occurrences. */
public record WikiRichText(String text, List<LinkSpan> links) {
    public WikiRichText {
        links = List.copyOf(links);
    }

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
        return new WikiRichText(text.substring(start, end), sliced);
    }

    WikiRichText truncate(int maxLength, String notice) {
        if (text.length() <= maxLength) return this;
        int end = text.substring(0, maxLength).stripTrailing().length();
        WikiRichText shortened = slice(0, end);
        return new WikiRichText(shortened.text() + notice, shortened.links());
    }
}
