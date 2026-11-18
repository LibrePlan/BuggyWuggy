/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.bugzilla;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bugzilla comments (plain text) as Markdown that looks the same: characters that Markdown
 * would take as formatting are escaped, but not inside web addresses (so that the import can
 * still recognise links to bugs and attachments). Quoted lines ({@code > …}, as Bugzilla writes
 * replies) stay quotes, and indented lines (stack traces) become code. "attachment 12" becomes
 * a link to the attachment, which the import points to the copied file. Comments that Bugzilla
 * 5.2 marks as Markdown are kept as they are.
 */
final class BugzillaText {

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"]+");
    /** Inline formatting: emphasis, code, links, strikethrough, HTML and escapes. */
    private static final Pattern INLINE = Pattern.compile("[\\\\*_`\\[\\]~<>|]");
    /** At the start of a line: headings, and lines Markdown would read as a heading underline or rule. */
    private static final Pattern LINE_START = Pattern.compile("(?m)^( {0,3})(#|=+\\s*$|-{3,}\\s*$|_{3,}\\s*$|\\*{3,}\\s*$)");
    private static final Pattern ATTACHMENT = Pattern.compile("(?i)\\b(attachment) (\\d+)\\b");

    private BugzillaText() {
    }

    /** {@code web}: the address of Bugzilla, for links to attachments. */
    static String toMarkdown(String text, boolean markdown, String web) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        if (markdown) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        for (String line : text.replace("\r\n", "\n").split("\n", -1)) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            out.append(line(line, web));
        }
        return LINE_START.matcher(out).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "\\" + m.group(2)));
    }

    private static String line(String line, String web) {
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
            out.append(prose(rest.substring(last, url.start()), web));
            out.append(url.group());
            last = url.end();
        }
        out.append(prose(rest.substring(last), web));
        return out.toString();
    }

    private static String prose(String text, String web) {
        String escaped = INLINE.matcher(text).replaceAll(m -> Matcher.quoteReplacement("\\" + m.group()));
        return ATTACHMENT.matcher(escaped).replaceAll(m -> Matcher.quoteReplacement(
                "[" + m.group(1) + " " + m.group(2) + "](" + web + "/attachment.cgi?id=" + m.group(2) + ")"));
    }
}
