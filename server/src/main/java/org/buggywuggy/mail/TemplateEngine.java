/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.nodes.Entities;

/**
 * The keyword language of mail templates: {@code {{name}}} is replaced by a value, nothing
 * else. Deliberately without logic, loops or expressions, so a template edited in the web
 * interface cannot run code.
 */
public final class TemplateEngine {

    /** A keyword and whether it stands for a block of HTML (only allowed in the body). */
    public record Keyword(String name, boolean block) {
    }

    /** A problem in a template; {@code line} is 1-based. */
    public record Problem(int line, String code, String keyword) {
    }

    private static final Pattern NAME = Pattern.compile("[a-z][a-zA-Z0-9]*(\\.[a-z][a-zA-Z0-9]*)*");
    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([^{}]*?)\\s*}}");

    private TemplateEngine() {
    }

    /** Finds unknown keywords, unclosed {@code {{}, and (in a subject) block keywords. */
    public static List<Problem> validate(String text, Map<String, Keyword> keywords, boolean subject) {
        List<Problem> problems = new ArrayList<>();
        int position = 0;
        while (true) {
            int open = text.indexOf("{{", position);
            if (open < 0) {
                break;
            }
            Matcher m = TOKEN.matcher(text);
            int next = text.indexOf("{{", open + 2);
            int close = text.indexOf("}}", open + 2);
            if (close < 0 || (next >= 0 && next < close) || !m.find(open) || m.start() != open) {
                problems.add(new Problem(lineOf(text, open), "mail-template.unclosed", null));
                position = open + 2;
                continue;
            }
            String name = m.group(1);
            Keyword keyword = keywords.get(name);
            if (!NAME.matcher(name).matches() || keyword == null) {
                problems.add(new Problem(lineOf(text, open), "mail-template.unknown-keyword", name));
            } else if (subject && keyword.block()) {
                problems.add(new Problem(lineOf(text, open), "mail-template.block-in-subject", name));
            }
            position = m.end();
        }
        return problems;
    }

    /**
     * Replaces the keywords. {@code value} gives the text for a keyword (or {@code null} for
     * an empty value); unknown keywords are left out. Values are inserted as they are: callers
     * escape them for the context (see {@link #renderHtml}).
     */
    public static String render(String text, Function<String, String> value) {
        Matcher m = TOKEN.matcher(text);
        StringBuilder out = new StringBuilder(text.length() + 256);
        while (m.find()) {
            String replacement = value.apply(m.group(1));
            m.appendReplacement(out, Matcher.quoteReplacement(replacement == null ? "" : replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    /**
     * Renders an HTML body: inline values are HTML-escaped, block keywords insert the HTML
     * {@code blocks} gives for them.
     */
    public static String renderHtml(String html, Map<String, Keyword> keywords, Function<String, String> value,
            Function<String, String> blocks) {
        return render(html, name -> {
            Keyword keyword = keywords.get(name);
            if (keyword == null) {
                return "";
            }
            if (keyword.block()) {
                return blocks.apply(name);
            }
            String text = value.apply(name);
            return text == null ? "" : Entities.escape(text);
        });
    }

    /** A subject is one line of plain text. */
    public static String renderSubject(String subject, Function<String, String> value) {
        String text = render(subject, value).replaceAll("[\\r\\n\\t]+", " ").strip();
        return text.length() > 250 ? text.substring(0, 249) + "…" : text;
    }

    private static int lineOf(String text, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
