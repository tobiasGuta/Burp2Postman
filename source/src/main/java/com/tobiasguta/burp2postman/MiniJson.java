package com.tobiasguta.burp2postman;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class MiniJson {
    private MiniJson() {}

    static Map<String, Object> object() {
        return new LinkedHashMap<>();
    }

    static List<Object> array() {
        return new ArrayList<>();
    }

    static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    static Object parse(String json) {
        Parser parser = new Parser(json == null ? "" : json);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("Unexpected trailing JSON at position " + parser.position());
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asObject(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    static List<Object> asArray(Object value) {
        return value instanceof List<?> ? (List<Object>) value : List.of();
    }

    static String text(Map<String, Object> object, String key) {
        Object value = object.get(key);
        return value instanceof String string ? string : "";
    }

    static int integer(Map<String, Object> object, String key, int fallback) {
        Object value = object.get(key);
        if (value instanceof Number number) return number.intValue();
        if (value instanceof String string) {
            try {
                return Integer.parseInt(string);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String string) {
            writeString(string, out);
        } else if (value instanceof Number || value instanceof Boolean) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) continue;
                if (!first) out.append(',');
                first = false;
                writeString(key, out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : iterable) {
                if (!first) out.append(',');
                first = false;
                write(item, out);
            }
            out.append(']');
        } else {
            writeString(String.valueOf(value), out);
        }
    }

    private static void writeString(String value, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                    else out.append(ch);
                }
            }
        }
        out.append('"');
    }

    private static final class Parser {
        private final String input;
        private int index;

        Parser(String input) {
            this.input = input;
        }

        int position() {
            return index;
        }

        boolean atEnd() {
            return index >= input.length();
        }

        void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(input.charAt(index))) index++;
        }

        Object parseValue() {
            skipWhitespace();
            if (atEnd()) throw error("Expected JSON value");
            return switch (input.charAt(index)) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> object = new LinkedHashMap<>();
            skipWhitespace();
            if (peek('}')) {
                index++;
                return object;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                object.put(key, parseValue());
                skipWhitespace();
                if (peek('}')) {
                    index++;
                    return object;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> array = new ArrayList<>();
            skipWhitespace();
            if (peek(']')) {
                index++;
                return array;
            }
            while (true) {
                array.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    index++;
                    return array;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder value = new StringBuilder();
            while (!atEnd()) {
                char ch = input.charAt(index++);
                if (ch == '"') return value.toString();
                if (ch != '\\') {
                    value.append(ch);
                    continue;
                }
                if (atEnd()) throw error("Incomplete escape sequence");
                char escaped = input.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> value.append(escaped);
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> value.append(parseUnicode());
                    default -> throw error("Invalid escape sequence");
                }
            }
            throw error("Unterminated string");
        }

        private char parseUnicode() {
            if (index + 4 > input.length()) throw error("Incomplete unicode escape");
            String hex = input.substring(index, index + 4);
            index += 4;
            try {
                return (char) Integer.parseInt(hex, 16);
            } catch (NumberFormatException e) {
                throw error("Invalid unicode escape");
            }
        }

        private Object parseLiteral(String literal, Object value) {
            if (!input.startsWith(literal, index)) throw error("Expected " + literal);
            index += literal.length();
            return value;
        }

        private Number parseNumber() {
            int start = index;
            if (peek('-')) index++;
            while (!atEnd() && Character.isDigit(input.charAt(index))) index++;
            boolean fractional = false;
            if (peek('.')) {
                fractional = true;
                index++;
                while (!atEnd() && Character.isDigit(input.charAt(index))) index++;
            }
            if (!atEnd() && (input.charAt(index) == 'e' || input.charAt(index) == 'E')) {
                fractional = true;
                index++;
                if (!atEnd() && (input.charAt(index) == '+' || input.charAt(index) == '-')) index++;
                while (!atEnd() && Character.isDigit(input.charAt(index))) index++;
            }
            if (start == index) throw error("Expected JSON value");
            String number = input.substring(start, index);
            try {
                if (fractional) {
                    return Double.parseDouble(number);
                }
                return Long.parseLong(number);
            } catch (NumberFormatException e) {
                throw error("Invalid number");
            }
        }

        private void expect(char expected) {
            skipWhitespace();
            if (atEnd() || input.charAt(index) != expected) throw error("Expected '" + expected + "'");
            index++;
        }

        private boolean peek(char value) {
            return !atEnd() && input.charAt(index) == value;
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at position " + index);
        }
    }
}
