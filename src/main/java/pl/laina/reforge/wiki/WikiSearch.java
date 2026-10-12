package pl.laina.reforge.wiki;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Deterministic search over both modeled items and newly indexed Wiki pages. */
final class WikiSearch {
    record Results(List<String> titles, boolean approximate) {
        Results { titles = List.copyOf(titles); }
    }
    private record Candidate(String title, String normalized, int score) {}

    static Results find(String query, Collection<String> titles) {
        String needle = WikiCatalog.normalize(query);
        if (needle.isBlank() || needle.length() > 128) return new Results(List.of(), false);
        var unique = new LinkedHashMap<String, String>();
        for (String title : titles) unique.putIfAbsent(WikiCatalog.titleKey(title), WikiCatalog.displayTitle(title));
        List<Candidate> candidates = unique.entrySet().stream()
                .map(entry -> new Candidate(entry.getValue(), WikiCatalog.normalize(entry.getValue()),
                        WikiCatalog.titleKey(query).equals(entry.getKey()) ? -1 : matchScore(needle, WikiCatalog.normalize(entry.getValue()))))
                .toList();
        Comparator<Candidate> order = Comparator.comparingInt(Candidate::score)
                .thenComparing(Candidate::normalized).thenComparing(Candidate::title);
        var direct = candidates.stream().filter(candidate -> candidate.score() < 1000).sorted(order)
                .map(Candidate::title).toList();
        if (!direct.isEmpty() || needle.length() < 4) return new Results(direct, false);
        int tolerance = needle.length() < 10 ? 1 : 2;
        var approximate = candidates.stream()
                .filter(candidate -> Math.abs(candidate.normalized().length() - needle.length()) <= tolerance)
                .map(candidate -> new Candidate(candidate.title(), candidate.normalized(), distance(needle, candidate.normalized())))
                .filter(candidate -> candidate.score() <= tolerance).sorted(order).limit(10)
                .map(Candidate::title).toList();
        return new Results(approximate, !approximate.isEmpty());
    }

    static List<String> suggestions(Collection<String> titles, String query, int limit) {
        String needle = WikiCatalog.normalize(query);
        var unique = new LinkedHashMap<String, String>();
        for (String title : titles) {
            String display = WikiCatalog.displayTitle(title);
            if (!display.isBlank() && (needle.isBlank() || WikiCatalog.normalize(display).contains(needle))) {
                unique.putIfAbsent(WikiCatalog.titleKey(display), display);
            }
        }
        return unique.values().stream().limit(Math.max(0, limit)).toList();
    }

    private static int matchScore(String query, String title) {
        if (title.equals(query)) return 0;
        if (title.startsWith(query)) return 1;
        if (title.contains(query)) return 2;
        for (String token : query.split(" ")) if (!title.contains(token)) return 1000;
        return 3;
    }

    private static int distance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j < previous.length; j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1));
            }
            previous = current;
        }
        return previous[right.length()];
    }
}
