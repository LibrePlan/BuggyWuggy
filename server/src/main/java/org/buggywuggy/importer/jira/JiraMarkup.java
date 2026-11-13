/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.jira;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts Jira wiki markup (what REST API v2 returns, on Cloud and Server) to Markdown: code
 * and noformat blocks, quotes, headings, lists, tables, text effects, links, mentions, issue
 * keys and images or links of attachments. What has no Markdown equivalent (colours, panels)
 * keeps only its text.
 */
final class JiraMarkup {

    /** What a text refers to outside itself. */
    interface Context {

        /** The link to an attachment of the issue by file name, or null when there is none. */
        String attachment(String fileName);

        /** How to show a mention: {@code [~jdoe]} or {@code [~accountid:5b10…]} gives {@code jdoe} or the id. */
        String mention(String user);
    }

    private static final Pattern CODE = Pattern.compile(
            "(?s)(?<!\\{)\\{code(?::([^}]*))?\\}(?!\\})(.*?)(?<!\\{)\\{code\\}(?!\\})"
                    + "|(?<!\\{)\\{noformat(?::[^}]*)?\\}(?!\\})(.*?)(?<!\\{)\\{noformat\\}(?!\\})");
    private static final Pattern QUOTE = Pattern.compile("(?s)\\{quote\\}(.*?)\\{quote\\}");
    private static final Pattern DROPPED = Pattern.compile(
            "\\{(?:color|panel|anchor|toc|section|column)(?::[^}]*)?\\}");
    private static final Pattern HEADING = Pattern.compile("^h([1-6])\\.\\s+(.*)$");
    private static final Pattern BLOCKQUOTE = Pattern.compile("^bq\\.\\s+(.*)$");
    private static final Pattern RULE = Pattern.compile("^\\s*-{4,}\\s*$");
    private static final Pattern LIST = Pattern.compile("^\\s*([*#]+|-)\\s+(.*)$");
    private static final Pattern MONO = Pattern.compile("\\{\\{(.+?)\\}\\}");
    private static final Pattern LINK = Pattern.compile("\\[([^\\[\\]\\n]*)\\]");
    private static final Pattern IMAGE = Pattern.compile(
            "!([^\\s!|][^!|\\n]*?\\.[A-Za-z0-9]{2,5})(?:\\|[^!\\n]*)?!");
    private static final Pattern BOLD = Pattern.compile("(?<![\\w*])\\*(?=\\S)([^*\\n]+?)(?<=\\S)\\*(?![\\w*])");
    private static final Pattern ITALIC = Pattern.compile("(?<![\\w_])_(?=\\S)([^_\\n]+?)(?<=\\S)_(?![\\w_])");
    private static final Pattern STRIKE = Pattern.compile("(?<![\\w-])-(?=\\S)([^-\\n]+?)(?<=\\S)-(?![\\w-])");
    private static final Pattern CITATION = Pattern.compile("\\?\\?(?=\\S)([^?\\n]+?)(?<=\\S)\\?\\?");
    private static final Pattern KEY = Pattern.compile("[A-Z][A-Z0-9_]+-\\d+");
    private static final char START = '\u0001';
    private static final char END = '\u0002';

    private final Context context;
    /** Parts that are finished and must not be converted again (code, links). */
    private final List<String> kept = new ArrayList<>();

    private JiraMarkup(Context context) {
        this.context = context;
    }

    static String toMarkdown(String text, Context context) {
        if (text == null || text.isBlank()) {
            return text;
        }
        return new JiraMarkup(context).convert(text.replace("\r\n", "\n").replace('\r', '\n'));
    }

    private String convert(String text) {
        StringBuilder out = new StringBuilder();
        Matcher code = CODE.matcher(text);
        int last = 0;
        while (code.find()) {
            out.append(blocks(text.substring(last, code.start())));
            if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
            String content = code.group(2) != null ? code.group(2) : code.group(3);
            content = content.replaceAll("^\\n+", "").replaceAll("\\n+$", "");
            String fence = content.contains("```") ? "~~~" : "```";
            out.append(fence).append(code.group(2) != null ? language(code.group(1)) : "").append('\n')
                    .append(content).append('\n').append(fence);
            last = code.end();
            if (last < text.length() && text.charAt(last) != '\n') {
                out.append('\n');
            }
        }
        out.append(blocks(text.substring(last)));
        return restore(out.toString());
    }

    /** {code:java}, {code:language=java|title=x}, {code:title=x} → java, java, nothing */
    private static String language(String parameters) {
        if (parameters == null) {
            return "";
        }
        for (String part : parameters.split("\\|")) {
            String value = part.strip();
            if (value.startsWith("language=")) {
                value = value.substring("language=".length());
            } else if (value.contains("=")) {
                continue;
            }
            if (value.matches("[\\w+#.-]+")) {
                return value.toLowerCase(Locale.ROOT);
            }
        }
        return "";
    }

    private String blocks(String text) {
        StringBuilder out = new StringBuilder();
        Matcher quote = QUOTE.matcher(text);
        int last = 0;
        while (quote.find()) {
            out.append(lines(text.substring(last, quote.start())));
            String inner = lines(quote.group(1).replaceAll("^\\n+", "").replaceAll("\\n+$", ""));
            if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
            out.append(String.join("\n", inner.lines().map(l -> l.isEmpty() ? ">" : "> " + l).toList()));
            last = quote.end();
        }
        out.append(lines(text.substring(last)));
        return out.toString();
    }

    private String lines(String text) {
        String[] lines = DROPPED.matcher(text).replaceAll("").split("\n", -1);
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            Matcher m;
            if ((m = HEADING.matcher(line)).matches()) {
                out.add("#".repeat(Integer.parseInt(m.group(1))) + " " + inline(m.group(2)));
            } else if ((m = BLOCKQUOTE.matcher(line)).matches()) {
                out.add("> " + inline(m.group(1)));
            } else if (RULE.matcher(line).matches()) {
                out.add("---");
            } else if ((m = LIST.matcher(line)).matches()) {
                out.add(listItem(m.group(1), m.group(2)));
            } else if (line.startsWith("||")) {
                List<String> cells = cells(line.substring(2), "\\|\\|");
                out.add(row(cells));
                out.add(row(cells.stream().map(c -> "---").toList()));
            } else if (line.startsWith("|")) {
                out.add(row(cells(line.substring(1), "\\|")));
            } else {
                out.add(inline(line));
            }
        }
        return String.join("\n", out);
    }

    /** * a, ** b, # c, *# d → nested Markdown lists, indented under the text of their parent. */
    private String listItem(String markers, String text) {
        StringBuilder indent = new StringBuilder();
        for (int i = 0; i < markers.length() - 1; i++) {
            indent.append(markers.charAt(i) == '#' ? "   " : "  ");
        }
        return indent + (markers.endsWith("#") ? "1. " : "- ") + inline(text);
    }

    /** The cells of a table row; | inside [links|…] does not separate cells. */
    private List<String> cells(String row, String separator) {
        String protectedLinks = LINK.matcher(row).replaceAll(m -> Matcher.quoteReplacement(keep(m.group())));
        String trimmed = protectedLinks.replaceAll("\\s*(?:" + separator + ")\\s*$", "");
        List<String> cells = new ArrayList<>();
        for (String cell : trimmed.split(separator, -1)) {
            cells.add(inline(restore(cell).strip()).replace("|", "\\|"));
        }
        return cells;
    }

    private static String row(List<String> cells) {
        return "| " + String.join(" | ", cells) + " |";
    }

    private String inline(String text) {
        String result = MONO.matcher(text).replaceAll(m -> Matcher.quoteReplacement(
                keep(m.group(1).contains("`") ? "`` " + m.group(1) + " ``" : "`" + m.group(1) + "`")));
        result = LINK.matcher(result).replaceAll(m -> Matcher.quoteReplacement(link(m.group(1), m.group())));
        result = IMAGE.matcher(result).replaceAll(m -> Matcher.quoteReplacement(image(m.group(1), m.group())));
        result = BOLD.matcher(result).replaceAll("**$1**");
        result = ITALIC.matcher(result).replaceAll("*$1*");
        result = STRIKE.matcher(result).replaceAll("~~$1~~");
        result = CITATION.matcher(result).replaceAll("*$1*");
        return result.replace("\\\\", "\n");
    }

    /** [text|url], [url], [^file], [~user], [ABC-12], [#anchor]. */
    private String link(String inner, String original) {
        if (inner.startsWith("~")) {
            String user = inner.substring(1);
            return keep("@" + context.mention(user.regionMatches(true, 0, "accountid:", 0, 10)
                    ? user.substring(10) : user));
        }
        String text = null;
        String target = inner;
        int bar = inner.lastIndexOf('|');
        if (bar >= 0) {
            text = inner.substring(0, bar).strip();
            target = inner.substring(bar + 1).strip();
        }
        if (target.startsWith("^")) {
            String file = target.substring(1);
            String url = context.attachment(file);
            return url == null ? (text == null || text.isEmpty() ? file : text)
                    : keep("[" + (text == null || text.isEmpty() ? file : text) + "](" + url + ")");
        }
        if (target.matches("(?i)(https?|ftp)://\\S+|mailto:\\S+")) {
            return keep(text == null || text.isEmpty() ? "<" + target + ">" : "[" + text + "](" + target + ")");
        }
        if (KEY.matcher(target).matches()) {
            return text == null || text.isEmpty() || text.equals(target) ? target : text + " (" + target + ")";
        }
        if (target.startsWith("#") || target.isEmpty()) {
            return text == null ? original : text;
        }
        return original;
    }

    private String image(String name, String original) {
        if (name.matches("(?i)https?://\\S+")) {
            return keep("![](" + name + ")");
        }
        String url = context.attachment(name);
        return url == null ? original : keep("![" + name + "](" + url + ")");
    }

    private String keep(String markdown) {
        kept.add(markdown);
        return START + String.valueOf(kept.size() - 1) + END;
    }

    private String restore(String text) {
        String result = text;
        // Kept parts may contain kept parts (a link in a table cell); repeat until none are left.
        for (int round = 0; round < 3 && result.indexOf(START) >= 0; round++) {
            StringBuilder out = new StringBuilder();
            int i = 0;
            while (i < result.length()) {
                char c = result.charAt(i);
                int end = c == START ? result.indexOf(END, i) : -1;
                if (end > i) {
                    out.append(kept.get(Integer.parseInt(result.substring(i + 1, end))));
                    i = end + 1;
                } else {
                    out.append(c);
                    i++;
                }
            }
            result = out.toString();
        }
        return result;
    }
}
