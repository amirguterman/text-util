package com.amirguterman.textutil;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Line-oriented search. All offsets are UTF-16 offsets suitable for EditText selection. */
public final class SearchEngine {
    private static final String INTEGER = "([-+]?\\d+)(?![\\d.])";
    private static final String NUMBER = "([-+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))(?![\\d.])";
    private static final Pattern COMPARISON = Pattern.compile("js\\(\\s*\\(?\\s*o\\s*\\)?\\s*=>\\s*o\\s*(<=|>=|===|==|<|>)\\s*([-+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+))\\s*\\)");

    public static final class Hit {
        public final int line, start, end;
        public final String preview;
        Hit(int line, int start, int end, String preview) {
            this.line = line; this.start = start; this.end = end; this.preview = preview;
        }
    }

    private interface Check { boolean matches(String value); }
    private static final class Constraint {
        final int group;
        final Check check;
        Constraint(int group, Check check) { this.group = group; this.check = check; }
    }
    private static final class Candidate {
        final Hit hit;
        final BigDecimal maximum;
        Candidate(Hit hit, BigDecimal maximum) { this.hit = hit; this.maximum = maximum; }
    }
    private static final class Query {
        final Pattern pattern;
        final List<Constraint> constraints;
        final int maxGroup;
        Query(Pattern pattern, List<Constraint> constraints, int maxGroup) {
            this.pattern = pattern; this.constraints = constraints; this.maxGroup = maxGroup;
        }
    }

    private SearchEngine() { }

    public static List<Hit> search(String text, String expression) {
        List<Hit> hits = new ArrayList<>();
        if (expression == null || expression.isEmpty()) return hits;
        Query query = compile(expression);
        List<Candidate> candidates = new ArrayList<>();
        int offset = 0, lineNumber = 1;
        while (offset <= text.length()) {
            int next = text.indexOf('\n', offset);
            if (next < 0) next = text.length();
            String line = text.substring(offset, next);
            Matcher matcher = query.pattern.matcher(line);
            while (matcher.find()) {
                boolean accepted = true;
                for (Constraint constraint : query.constraints) {
                    if (!constraint.check.matches(matcher.group(constraint.group))) { accepted = false; break; }
                }
                if (accepted) {
                    BigDecimal max = query.maxGroup < 0 ? null : new BigDecimal(matcher.group(query.maxGroup));
                    candidates.add(new Candidate(new Hit(lineNumber, offset + matcher.start(), offset + matcher.end(), line.trim()), max));
                }
            }
            if (next == text.length()) break;
            offset = next + 1;
            lineNumber++;
        }
        BigDecimal maximum = null;
        if (query.maxGroup >= 0) {
            for (Candidate candidate : candidates)
                if (maximum == null || candidate.maximum.compareTo(maximum) > 0) maximum = candidate.maximum;
        }
        for (Candidate candidate : candidates)
            if (maximum == null || candidate.maximum == null || candidate.maximum.compareTo(maximum) == 0)
                hits.add(candidate.hit);
        return hits;
    }

    private static Query compile(String expression) {
        if (expression.startsWith("regex:")) {
            String regex = expression.substring(6);
            if (regex.isEmpty()) throw new IllegalArgumentException("Enter a pattern after regex:");
            try { return new Query(Pattern.compile(regex), new ArrayList<>(), -1); }
            catch (PatternSyntaxException e) { throw new IllegalArgumentException("Invalid regex: " + e.getDescription()); }
        }
        StringBuilder regex = new StringBuilder();
        List<Constraint> constraints = new ArrayList<>();
        int groups = 0, maxGroup = -1;
        for (int i = 0; i < expression.length();) {
            if (expression.charAt(i) != '{') {
                int end = expression.indexOf('{', i);
                if (end < 0) end = expression.length();
                regex.append(Pattern.quote(expression.substring(i, end)));
                i = end;
                continue;
            }
            int end = closeBrace(expression, i);
            if (end < 0) throw new IllegalArgumentException("Unclosed { at position " + (i + 1));
            String token = expression.substring(i + 1, end).trim();
            if (token.equals("int.max()")) {
                if (maxGroup >= 0) throw new IllegalArgumentException("Use int.max() only once per query");
                regex.append(INTEGER); maxGroup = ++groups;
            } else if (token.startsWith("int.range(") && token.endsWith(")")) {
                String[] bounds = token.substring(10, token.length() - 1).split(",", -1);
                if (bounds.length != 2) throw new IllegalArgumentException("int.range needs two integer bounds");
                long lo = integer(bounds[0]), hi = integer(bounds[1]);
                if (lo > hi) throw new IllegalArgumentException("Range start exceeds end");
                regex.append(INTEGER); int group = ++groups;
                constraints.add(new Constraint(group, value -> {
                    try { long n = Long.parseLong(value); return n >= lo && n <= hi; }
                    catch (NumberFormatException e) { return false; }
                }));
            } else if (token.startsWith("int.lt(") && token.endsWith(")")) {
                long limit = integer(token.substring(7, token.length() - 1));
                regex.append(INTEGER); int group = ++groups;
                constraints.add(new Constraint(group, value -> {
                    try { return Long.parseLong(value) < limit; }
                    catch (NumberFormatException e) { return false; }
                }));
            } else if (token.equals("type.decimal")) {
                regex.append("([-+]?(?:\\d+\\.\\d+|\\.\\d+))(?![\\d.])"); groups++;
            } else if (token.startsWith("number.in(") && token.endsWith(")")) {
                List<String> values = list(token.substring(10, token.length() - 1));
                List<BigDecimal> numbers = new ArrayList<>();
                for (String value : values) {
                    try { numbers.add(new BigDecimal(value)); }
                    catch (NumberFormatException e) { throw new IllegalArgumentException("number.in accepts numeric values only"); }
                }
                regex.append(NUMBER); int group = ++groups;
                constraints.add(new Constraint(group, value -> {
                    BigDecimal n = new BigDecimal(value);
                    for (BigDecimal item : numbers) if (item.compareTo(n) == 0) return true;
                    return false;
                }));
            } else if (token.startsWith("oneof(") && token.endsWith(")")) {
                List<String> values = list(token.substring(6, token.length() - 1));
                List<String> strings = new ArrayList<>();
                List<BigDecimal> numbers = new ArrayList<>();
                List<Check> predicates = new ArrayList<>();
                for (String value : values) {
                    if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) strings.add(value.substring(1, value.length() - 1));
                    else if (value.startsWith("js(")) predicates.add(predicate(value));
                    else {
                        try { numbers.add(new BigDecimal(value)); }
                        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid oneof item: " + value); }
                    }
                }
                // Use alternatives to avoid swallowing the rest of a word or number.
                regex.append("((?:[-+]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)|[^\\s:,;]+))(?![\\d.])"); int group = ++groups;
                constraints.add(new Constraint(group, value -> {
                    if (strings.contains(value)) return true;
                    try {
                        BigDecimal number = new BigDecimal(value);
                        for (BigDecimal item : numbers) if (item.compareTo(number) == 0) return true;
                        for (Check check : predicates) if (check.matches(value)) return true;
                    } catch (NumberFormatException ignored) { }
                    return false;
                }));
            } else throw new IllegalArgumentException("Unknown placeholder: {" + token + "}");
            i = end + 1;
        }
        return new Query(Pattern.compile(regex.toString()), constraints, maxGroup);
    }

    private static long integer(String value) {
        try { return Long.parseLong(value.trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Expected an integer: " + value.trim()); }
    }

    private static int closeBrace(String input, int from) {
        boolean quoted = false, escaped = false;
        for (int i = from + 1; i < input.length(); i++) {
            char c = input.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && quoted) { escaped = true; continue; }
            if (c == '"') quoted = !quoted;
            if (c == '}' && !quoted) return i;
        }
        return -1;
    }

    private static List<String> list(String input) {
        String trimmed = input.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) throw new IllegalArgumentException("Expected a list in [brackets]");
        String body = trimmed.substring(1, trimmed.length() - 1);
        List<String> result = new ArrayList<>();
        boolean quoted = false; int depth = 0, start = 0;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == '"' && (i == 0 || body.charAt(i - 1) != '\\')) quoted = !quoted;
            if (!quoted) {
                if (c == '(') depth++;
                if (c == ')') depth--;
                if (c == ',' && depth == 0) {
                    result.add(body.substring(start, i).trim()); start = i + 1;
                }
            }
        }
        if (quoted || depth != 0) throw new IllegalArgumentException("Unbalanced list item");
        result.add(body.substring(start).trim());
        if (result.stream().anyMatch(String::isEmpty)) throw new IllegalArgumentException("Empty list item");
        return result;
    }

    private static Check predicate(String source) {
        Matcher matcher = COMPARISON.matcher(source);
        if (!matcher.matches()) throw new IllegalArgumentException("js supports only (o) => o <, <=, >, >=, ==, or === number");
        String op = matcher.group(1);
        BigDecimal limit = new BigDecimal(matcher.group(2));
        return value -> {
            try {
                int comparison = new BigDecimal(value).compareTo(limit);
                switch (op) {
                    case "<": return comparison < 0;
                    case "<=": return comparison <= 0;
                    case ">": return comparison > 0;
                    case ">=": return comparison >= 0;
                    default: return comparison == 0;
                }
            } catch (NumberFormatException e) { return false; }
        };
    }
}
