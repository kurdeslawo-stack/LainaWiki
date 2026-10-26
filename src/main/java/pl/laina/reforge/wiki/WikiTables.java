package pl.laina.reforge.wiki;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Converts Wiki tables before link extraction so cell links keep their click targets. */
final class WikiTables {
    private static final Pattern TABLE = Pattern.compile("(?ms)^\\s*\\{\\|[^\\n]*\\n(.*?)^\\s*\\|}[^\\n]*");

    static String normalize(String source) {
        return TABLE.matcher(source).replaceAll(match -> java.util.regex.Matcher.quoteReplacement(render(match.group(1))));
    }

    private static String render(String body) {
        List<String> headers = new ArrayList<>();
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        String caption = "";
        boolean headerRow = false;
        for (String raw : body.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("|-")) {
                if (!row.isEmpty()) {
                    if (headerRow && headers.isEmpty()) headers.addAll(row); else rows.add(List.copyOf(row));
                    row.clear();
                }
                headerRow = false;
            } else if (line.startsWith("|+")) {
                caption = cell(line.substring(2));
            } else if (line.startsWith("!") || line.startsWith("|")) {
                if (row.isEmpty()) headerRow = line.startsWith("!");
                row.addAll(cells(line.substring(1), line.startsWith("!") ? "!!" : "||"));
            } else if (!row.isEmpty() && !line.isBlank()) {
                int last = row.size() - 1;
                row.set(last, row.get(last) + " " + line);
            }
        }
        if (!row.isEmpty()) {
            if (headerRow && headers.isEmpty()) headers.addAll(row); else rows.add(List.copyOf(row));
        }
        StringBuilder result = new StringBuilder("\n\n");
        if (!caption.isBlank()) result.append(caption).append("\n\n");
        for (List<String> values : rows) {
            if (dailyRewards(headers, values)) {
                result.append("• Dzień ").append(values.get(0)).append(" — ").append(values.get(1)).append('\n');
                result.append("Bez gildii: ").append(values.get(2)).append(" · Gildia: ").append(values.get(3)).append('\n');
                result.append("Gildia od poziomu 5: ").append(values.get(4)).append(" · Gildia poziom 10: ").append(values.get(5));
            } else {
                for (int i = 0; i < values.size(); i++) {
                    if (i > 0) result.append('\n'); else result.append("• ");
                    if (i < headers.size() && !headers.get(i).isBlank()) result.append(headers.get(i)).append(": ");
                    result.append(values.get(i));
                }
            }
            result.append("\n\n");
        }
        return result.toString();
    }

    private static boolean dailyRewards(List<String> headers, List<String> values) {
        return headers.size() == 6 && values.size() == 6
                && headers.get(0).equals("Dzień") && headers.get(1).equals("Nagroda")
                && headers.get(2).equals("Ilość, jeśli nie należysz do gildii")
                && headers.get(3).equals("Ilość, jeśli należysz do gildii")
                && headers.get(4).equals("Ilość, jeśli gildia posiada przynajmniej 5 poziom")
                && headers.get(5).equals("Ilość, jeśli gildia posiada 10 poziom");
    }

    private static List<String> cells(String source, String delimiter) {
        List<String> result = new ArrayList<>();
        int links = 0, templates = 0, start = 0;
        for (int i = 0; i < source.length() - 1; i++) {
            String pair = source.substring(i, i + 2);
            if (pair.equals("[[")) { links++; i++; }
            else if (pair.equals("]]")) { links--; i++; }
            else if (pair.equals("{{")) { templates++; i++; }
            else if (pair.equals("}}")) { templates--; i++; }
            else if (links == 0 && templates == 0 && pair.equals(delimiter)) {
                result.add(cell(source.substring(start, i)));
                start = i + 2;
                i++;
            }
        }
        result.add(cell(source.substring(start)));
        return result;
    }

    private static String cell(String value) {
        // Only a leading attribute block is syntax; pipes inside [[target|label]] are content.
        return value.strip().replaceFirst("(?is)^(?:(?:style|class|align|valign|width|height|rowspan|colspan)\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s|]+)\\s*)+\\|\\s*", "");
    }
}
