// Copyright (C) 2026 Miguel Arregui
// SPDX-License-Identifier: Apache-2.0

package dev.jfrq.core.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict JSON parser for the tests, so that {@code --json} output is checked as a program
 * would read it rather than as a string: objects are {@link Map}s in document order, arrays
 * {@link List}s, integers {@link Long}, other numbers {@link Double}, and {@code null} is null.
 * Anything RFC 8259 does not allow (a trailing comma, a bare NaN, a control character in a
 * string, text after the value) is an {@link IllegalArgumentException} naming the offset.
 */
public final class JsonParser {

    private final String s;
    private int i;

    private JsonParser(final String s) {
        this.s = s;
    }

    public static Object parse(final String json) {
        final JsonParser p = new JsonParser(json);
        p.space();
        final Object value = p.value();
        p.space();
        if (p.i != json.length()) {
            throw p.fail("text after the value");
        }
        return value;
    }

    @SuppressWarnings("unchecked") // the caller knows the document's shape
    public static Map<String, Object> object(final String json) {
        return (Map<String, Object>) parse(json);
    }

    private Object value() {
        if (i >= s.length()) {
            throw fail("end of input");
        }
        final char c = s.charAt(i);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        final Map<String, Object> out = new LinkedHashMap<>();
        i++;
        space();
        if (peek() == '}') {
            i++;
            return out;
        }
        while (true) {
            space();
            if (peek() != '"') {
                throw fail("a member name");
            }
            final String name = string();
            if (out.containsKey(name)) {
                throw fail("duplicate member " + name);
            }
            space();
            expect(':');
            space();
            out.put(name, value());
            space();
            if (peek() == ',') {
                i++;
                continue;
            }
            expect('}');
            return out;
        }
    }

    private List<Object> array() {
        final List<Object> out = new ArrayList<>();
        i++;
        space();
        if (peek() == ']') {
            i++;
            return out;
        }
        while (true) {
            space();
            out.add(value());
            space();
            if (peek() == ',') {
                i++;
                continue;
            }
            expect(']');
            return out;
        }
    }

    private String string() {
        final StringBuilder sb = new StringBuilder();
        i++;
        while (true) {
            if (i >= s.length()) {
                throw fail("an unterminated string");
            }
            final char c = s.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c < 0x20) {
                throw fail("a control character in a string");
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            final char e = s.charAt(i++);
            switch (e) {
                case '"', '\\', '/' -> sb.append(e);
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> throw fail("an unknown escape \\" + e);
            }
        }
    }

    private Object number() {
        final int from = i;
        if (peek() == '-') {
            i++;
        }
        if (peek() == '0') {
            i++;
        } else if (Character.isDigit(peek())) {
            while (Character.isDigit(peek())) {
                i++;
            }
        } else {
            throw fail("a value");
        }
        boolean integral = true;
        if (peek() == '.') {
            integral = false;
            i++;
            digits();
        }
        if (peek() == 'e' || peek() == 'E') {
            integral = false;
            i++;
            if (peek() == '+' || peek() == '-') {
                i++;
            }
            digits();
        }
        final String text = s.substring(from, i);
        return integral ? (Object) Long.parseLong(text) : (Object) Double.parseDouble(text);
    }

    private void digits() {
        if (!Character.isDigit(peek())) {
            throw fail("a digit");
        }
        while (Character.isDigit(peek())) {
            i++;
        }
    }

    private Object literal(final String word, final Object value) {
        if (!s.startsWith(word, i)) {
            throw fail("a value");
        }
        i += word.length();
        return value;
    }

    private void space() {
        while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\n' || s.charAt(i) == '\r'
                || s.charAt(i) == '\t')) {
            i++;
        }
    }

    private char peek() {
        return i < s.length() ? s.charAt(i) : '\0';
    }

    private void expect(final char c) {
        if (peek() != c) {
            throw fail("'" + c + "'");
        }
        i++;
    }

    private IllegalArgumentException fail(final String expected) {
        return new IllegalArgumentException("expected " + expected + " at offset " + i + " of " + s.length());
    }
}
