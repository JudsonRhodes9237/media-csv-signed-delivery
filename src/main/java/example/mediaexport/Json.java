package example.mediaexport;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class Json {
    private Json() {}

    static Object parse(String text) {
        Parser parser = new Parser(text);
        Object value = parser.value();
        parser.space();
        if (!parser.end()) throw new IllegalArgumentException("Trailing JSON content");
        return value;
    }

    static String object(Map<String, ?> values) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (var entry : values.entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append(quote(entry.getKey())).append(':').append(render(entry.getValue()));
        }
        return out.append('}').toString();
    }

    static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('\"').toString();
    }

    private static String render(Object value) {
        if (value == null) return "null";
        if (value instanceof String string) return quote(string);
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> typed = new LinkedHashMap<>();
            map.forEach((key, item) -> typed.put(String.valueOf(key), item));
            return object(typed);
        }
        throw new IllegalArgumentException("Unsupported JSON value");
    }

    private static final class Parser {
        private final String text;
        private int at;

        private Parser(String text) { this.text = text; }
        private boolean end() { return at == text.length(); }
        private void space() { while (!end() && Character.isWhitespace(text.charAt(at))) at++; }

        private Object value() {
            space();
            if (end()) throw new IllegalArgumentException("Expected JSON value");
            return switch (text.charAt(at)) {
                case '{' -> map();
                case '[' -> list();
                case '\"' -> string();
                case 't' -> literal("true", true);
                case 'f' -> literal("false", false);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> map() {
            at++;
            Map<String, Object> result = new LinkedHashMap<>();
            space();
            if (take('}')) return result;
            do {
                space();
                String key = string();
                space();
                expect(':');
                result.put(key, value());
                space();
            } while (take(','));
            expect('}');
            return result;
        }

        private List<Object> list() {
            at++;
            List<Object> result = new ArrayList<>();
            space();
            if (take(']')) return result;
            do {
                result.add(value());
                space();
            } while (take(','));
            expect(']');
            return result;
        }

        private String string() {
            expect('\"');
            StringBuilder out = new StringBuilder();
            while (!end()) {
                char c = text.charAt(at++);
                if (c == '\"') return out.toString();
                if (c != '\\') { out.append(c); continue; }
                if (end()) throw new IllegalArgumentException("Incomplete JSON escape");
                char escaped = text.charAt(at++);
                switch (escaped) {
                    case '\"', '\\', '/' -> out.append(escaped);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> {
                        if (at + 4 > text.length()) throw new IllegalArgumentException("Incomplete Unicode escape");
                        out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        at += 4;
                    }
                    default -> throw new IllegalArgumentException("Invalid JSON escape");
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private Object number() {
            int start = at;
            while (!end() && "-+0123456789.eE".indexOf(text.charAt(at)) >= 0) at++;
            String token = text.substring(start, at);
            if (token.isEmpty()) throw new IllegalArgumentException("Invalid JSON value");
            return token.contains(".") || token.contains("e") || token.contains("E")
                    ? Double.parseDouble(token) : Long.parseLong(token);
        }

        private Object literal(String token, Object value) {
            if (!text.startsWith(token, at)) throw new IllegalArgumentException("Invalid JSON literal");
            at += token.length();
            return value;
        }

        private boolean take(char expected) {
            if (!end() && text.charAt(at) == expected) { at++; return true; }
            return false;
        }

        private void expect(char expected) {
            if (!take(expected)) throw new IllegalArgumentException("Expected " + expected);
        }
    }
}
