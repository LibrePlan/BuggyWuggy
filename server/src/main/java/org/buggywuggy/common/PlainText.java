/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.common;

import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Plain text (a mail, a Bugzilla comment) as Markdown that looks the same: characters that
 * Markdown would take as formatting are escaped, but not inside web addresses (so that links
 * stay links). Quoted lines ({@code > …}, as replies are written) stay quotes, and indented
 * lines (stack traces) become code.
 */
public final class PlainText {

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"]+");
    /** Inline formatting: emphasis, code, links, strikethrough, HTML and escapes. */
    private static final Pattern INLINE = Pattern.compile("[\\\\*_`\\[\\]~<>|]");
    /** At the start of a line: headings, and lines Markdown would read as a heading underline or rule. */
    private static final Pattern LINE_START = Pattern.compile("(?m)^( {0,3})(#|=+\\s*$|-{3,}\\s*$|_{3,}\\s*$|\\*{3,}\\s*$)");

    private PlainText() {
    }

    public static String toMarkdown(String text) {
        return toMarkdown(text, UnaryOperator.identity());
    }

    /** {@code prose}: applied to escaped text outside addresses and code (e.g. to add links). */
    public static String toMarkdown(String text, UnaryOperator<String> prose) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(line(line, prose));
        }
        return LINE_START.matcher(out).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "\\" + m.group(2)));
    }

    private static String line(String line, UnaryOperator<String> prose) {
        // Quotes stay quotes: "> text" (also "> > text").
        int quote = 0;
        while (quote < line.length() && (line.charAt(quote) == '>' || line.charAt(quote) == ' ')) {
            if (line.charAt(quote) == ' ' && (quote == 0 || line.charAt(quote - 1) != '>')) {
                break;
            }
            quote++;
        }
        String prefix = line.substring(0, quote);
        String rest = line.substring(quote);
        // Indented by four spaces or a tab: code, as in Markdown; nothing to escape there.
        if (rest.startsWith("    ") || rest.startsWith("\t")) {
            return prefix + rest;
        }
        StringBuilder out = new StringBuilder(prefix);
        Matcher url = URL.matcher(rest);
        int last = 0;
        while (url.find()) {
            out.append(prose.apply(escape(rest.substring(last, url.start()))));
            out.append(url.group());
            last = url.end();
        }
        out.append(prose.apply(escape(rest.substring(last))));
        return out.toString();
    }

    private static String escape(String text) {
        return INLINE.matcher(text).replaceAll(m -> Matcher.quoteReplacement("\\" + m.group()));
    }
}
