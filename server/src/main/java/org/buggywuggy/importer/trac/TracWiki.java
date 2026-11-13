/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.trac;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts Trac's WikiFormatting to Markdown: code blocks ({@code {{{ … }}}} with
 * {@code #!python}), headings, lists, tables, quotes, text effects, links ({@code [url text]},
 * {@code [[target|text]]}, {@code wiki:Page}, {@code ticket:12}, {@code attachment:file}) and
 * images ({@code [[Image(file.png)]]}). Ticket references become {@code #12}, which the import
 * engine rewrites; other TracLinks (milestones, reports, changesets, source) become links to Trac.
 */
final class TracWiki {

    /** What a text refers to outside itself. */
    interface Context {

        /**
         * The link to an attachment by file name: of this ticket ({@code ticket} null), or of
         * another ticket; null when this ticket has no such attachment.
         */
        String attachment(String ticket, String fileName);

        /** The web address of Trac, e.g. https://trac.example.org/project. */
        String web();
    }

    private static final Pattern CODE_BLOCK = Pattern.compile(
            "(?ms)^[ \\t]*\\{\\{\\{[ \\t]*\\n(?:[ \\t]*#!([\\w+#.-]+)[^\\n]*\\n)?(.*?)\\n?^[ \\t]*\\}\\}\\}[ \\t]*$");
    private static final Pattern HEADING = Pattern.compile("^\\s*(={1,6})\\s+(.+?)\\s*(?:=+)?\\s*(?:#\\S+)?\\s*$");
    private static final Pattern RULE = Pattern.compile("^\\s*-{4,}\\s*$");
    private static final Pattern LIST = Pattern.compile("^( +)([*-]|\\d+\\.|[a-zA-Z]\\.|[ivxIVX]+\\.)\\s+(.*)$");
    private static final Pattern ROW = Pattern.compile("^\\s*\\|\\|.*\\|\\|\\s*$");
    private static final Pattern INDENTED = Pattern.compile("^ {2,}(\\S.*)$");

    private static final Pattern INLINE_CODE = Pattern.compile("\\{\\{\\{(.+?)\\}\\}\\}|`([^`\\n]+)`");
    private static final Pattern MACRO = Pattern.compile("\\[\\[(\\w+)(?:\\(([^)\\]]*)\\))?\\]\\]");
    private static final Pattern NEW_LINK = Pattern.compile("(!)?\\[\\[([^\\[\\]|\\n]+?)(?:\\|([^\\[\\]\\n]*))?\\]\\]");
    private static final Pattern OLD_LINK = Pattern.compile("(!)?\\[(?!\\[)((?:[\\w+.-]+:|/|#)[^\\s\\]]*"
            + "|[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+)(?:\\s+([^\\]\\n]*))?\\]");
    private static final Pattern BARE = Pattern.compile("(!)?(?<![\\w/:\\[])((?:https?|ftp)://[^\\s<>\\]\\)\"]+"
            + "|(?:ticket|wiki|attachment|milestone|report|changeset|source):[^\\s<>\\]\\)\"]+)");
    private static final Pattern ESCAPE = Pattern.compile("!(?=[A-Z][a-z]|#\\d|r\\d|\\[)");
    private static final Pattern BOLD_ITALIC = Pattern.compile("'''''(.+?)'''''");
    private static final Pattern BOLD = Pattern.compile("'''(.+?)'''|(?<!\\*)\\*\\*(?=\\S)(.+?)(?<=\\S)\\*\\*(?!\\*)");
    private static final Pattern ITALIC = Pattern.compile("''(.+?)''|(?<![:\\w/])//(?=\\S)(.+?)(?<=\\S)//(?![\\w/])");
    private static final Pattern UNDERLINE = Pattern.compile("__(?=\\S)(.+?)(?<=\\S)__");
    private static final Pattern TRAILING = Pattern.compile("[.,;:!?)]+$");

    private static final char START = '\u0001';
    private static final char END = '\u0002';

    private final Context context;
    private final List<String> kept = new ArrayList<>();

    private TracWiki(Context context) {
        this.context = context;
    }

    static String toMarkdown(String text, Context context) {
        if (text == null || text.isBlank()) {
            return text;
        }
        TracWiki converter = new TracWiki(context);
        return converter.restore(converter.convert(text.replace("\r\n", "\n").replace('\r', '\n')));
    }

    private String convert(String text) {
        StringBuilder out = new StringBuilder();
        Matcher code = CODE_BLOCK.matcher(text);
        int last = 0;
        while (code.find()) {
            out.append(blocks(text.substring(last, code.start())));
            String processor = code.group(1) == null ? "" : code.group(1).toLowerCase(Locale.ROOT);
            String content = code.group(2);
            if (!processor.equals("comment")) {
                String language = processor.equals("default") || processor.equals("div") ? "" : processor;
                String fence = content.contains("```") ? "~~~" : "```";
                out.append(keep(fence + language + "\n" + content + "\n" + fence));
            }
            last = code.end();
        }
        return out.append(blocks(text.substring(last))).toString();
    }

    private String blocks(String text) {
        String[] lines = text.split("\n", -1);
        List<String> out = new ArrayList<>();
        List<Integer> listIndents = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher m;
            if (!(m = LIST.matcher(line)).matches()) {
                if (!line.isBlank() && !line.startsWith(" ")) {
                    listIndents.clear();
                }
            }
            if ((m = HEADING.matcher(line)).matches()) {
                out.add("#".repeat(m.group(1).length()) + " " + inline(m.group(2)));
            } else if (RULE.matcher(line).matches()) {
                out.add("---");
            } else if ((m = LIST.matcher(line)).matches()) {
                int indent = m.group(1).length();
                while (!listIndents.isEmpty() && listIndents.getLast() > indent) {
                    listIndents.removeLast();
                }
                if (listIndents.isEmpty() || listIndents.getLast() < indent) {
                    listIndents.add(indent);
                }
                String marker = m.group(2).matches("[*-]") ? "- " : "1. ";
                out.add("  ".repeat(listIndents.size() - 1) + (listIndents.size() > 1 ? " " : "") + marker
                        + inline(m.group(3)));
            } else if (ROW.matcher(line).matches()) {
                int end = i;
                List<String> rows = new ArrayList<>();
                while (end < lines.length && ROW.matcher(lines[end]).matches()) {
                    rows.add(lines[end++]);
                }
                out.addAll(table(rows));
                i = end - 1;
            } else if ((m = INDENTED.matcher(line)).matches() && listIndents.isEmpty()) {
                out.add("> " + inline(m.group(1)));
            } else {
                out.add(inline(line));
            }
        }
        return String.join("\n", out);
    }

    /** ||= Name =||= Value =|| and ||a||b||; Markdown needs a header, so the first row is one. */
    private List<String> table(List<String> rows) {
        List<String> out = new ArrayList<>();
        for (String row : rows) {
            String inner = atoms(row.strip());
            inner = inner.substring(2, inner.length() - 2);
            List<String> cells = new ArrayList<>();
            for (String cell : inner.split("\\|\\|", -1)) {
                String content = cell.strip().replaceAll("^=\\s*(.*?)\\s*=$", "$1");
                cells.add(restore(effects(content)).replace("|", "\\|"));
            }
            out.add("| " + String.join(" | ", cells) + " |");
            if (out.size() == 1) {
                out.add("| " + String.join(" | ", cells.stream().map(c -> "---").toList()) + " |");
            }
        }
        return out;
    }

    private String inline(String text) {
        return effects(atoms(text));
    }

    /** Code, macros, links and URLs: finished, so text effects do not touch them. */
    private String atoms(String text) {
        String result = INLINE_CODE.matcher(text).replaceAll(m -> Matcher.quoteReplacement(keep(code(
                m.group(1) != null ? m.group(1) : m.group(2)))));
        result = MACRO.matcher(result).replaceAll(m -> Matcher.quoteReplacement(macro(m)));
        result = NEW_LINK.matcher(result).replaceAll(m -> Matcher.quoteReplacement(m.group(1) != null
                ? keep(m.group().substring(1)) : link(m.group(2).strip(), m.group(3), m.group())));
        result = OLD_LINK.matcher(result).replaceAll(m -> Matcher.quoteReplacement(m.group(1) != null
                ? keep(m.group().substring(1).replace("[", "\\[")) : link(m.group(2), m.group(3), m.group())));
        result = BARE.matcher(result).replaceAll(m -> Matcher.quoteReplacement(bare(m)));
        return ESCAPE.matcher(result).replaceAll("");
    }

    private String effects(String text) {
        String result = BOLD_ITALIC.matcher(text).replaceAll("***$1***");
        result = BOLD.matcher(result).replaceAll(m -> Matcher.quoteReplacement(
                "**" + (m.group(1) != null ? m.group(1) : m.group(2)) + "**"));
        result = ITALIC.matcher(result).replaceAll(m -> Matcher.quoteReplacement(
                "*" + (m.group(1) != null ? m.group(1) : m.group(2)) + "*"));
        return UNDERLINE.matcher(result).replaceAll("$1");
    }

    private static String code(String content) {
        return content.contains("`") ? "`` " + content + " ``" : "`" + content + "`";
    }

    /** [[Image(…)]], [[BR]]; tables of contents disappear; other macros stay as they are. */
    private String macro(MatchResult m) {
        String name = m.group(1);
        if (name.equalsIgnoreCase("BR")) {
            return "\n";
        }
        if (name.equals("PageOutline") || name.equals("TOC")) {
            return "";
        }
        if (!name.equals("Image") || m.group(2) == null) {
            return keep(m.group());
        }
        String source = m.group(2).split(",")[0].strip();
        String url;
        String shown = source;
        if (source.matches("(?i)(https?|ftp)://\\S+")) {
            url = source;
        } else {
            // file, ticket:12:file, or source:… / wiki:… (not copied)
            String ticket = null;
            String file = source;
            Matcher t = Pattern.compile("^(?:ticket:(\\d+):|attachment:)?(.+?)(?::ticket:(\\d+))?$").matcher(source);
            if (t.matches()) {
                ticket = t.group(1) != null ? t.group(1) : t.group(3);
                file = t.group(2);
            }
            shown = file;
            url = file.contains(":") ? null : context.attachment(ticket, file);
        }
        return url == null ? keep(m.group()) : keep("![" + shown + "](" + url + ")");
    }

    private String bare(MatchResult m) {
        if (m.group(1) != null) {
            return keep(m.group(2)); // !wiki:Page: not a link
        }
        String target = m.group(2);
        String after = "";
        Matcher trailing = TRAILING.matcher(target);
        if (trailing.find()) {
            after = target.substring(trailing.start());
            target = target.substring(0, trailing.start());
        }
        if (target.matches("(?i)(https?|ftp)://.+")) {
            return keep(target) + after;
        }
        return link(target, null, target) + after;
    }

    /** A TracLink with an optional text, as Markdown; {@code original} when it is not one. */
    private String link(String target, String text, String original) {
        String label = text == null || text.isBlank() ? null : text.strip();
        String ticket = ticketNumber(target);
        if (ticket != null) {
            // The engine turns #12 into the key of the imported ticket.
            return label == null || label.equals("#" + ticket) || label.equals("ticket:" + ticket) ? "#" + ticket
                    : label + " (#" + ticket + ")";
        }
        String url = url(target);
        if (url == null) {
            return original.startsWith("[") ? (label != null ? label : target) : original;
        }
        if (label == null && target.matches("(?i)(https?|ftp|mailto):.+")) {
            return keep("<" + url + ">");
        }
        String shown = label != null ? label : target.replaceFirst("^(?:wiki|attachment|milestone):", "");
        return keep("[" + shown + "](" + url + ")");
    }

    /** ticket:12, #12 → 12 */
    private static String ticketNumber(String target) {
        Matcher m = Pattern.compile("^(?:ticket:|#)(\\d+)$").matcher(target);
        return m.matches() ? m.group(1) : null;
    }

    private String url(String target) {
        String web = context.web();
        if (target.matches("(?i)(https?|ftp)://\\S+|mailto:\\S+")) {
            return target;
        }
        int colon = target.indexOf(':');
        String scheme = colon > 0 ? target.substring(0, colon).toLowerCase(Locale.ROOT) : "";
        String rest = colon > 0 ? target.substring(colon + 1) : target;
        return switch (scheme) {
            case "wiki" -> web + "/wiki/" + path(rest);
            case "attachment" -> {
                Matcher m = Pattern.compile("^(.+?)(?::ticket:(\\d+))?$").matcher(rest);
                yield m.matches() ? context.attachment(m.group(2), m.group(1)) : null;
            }
            case "milestone" -> web + "/milestone/" + path(rest);
            case "report" -> web + "/report/" + path(rest);
            case "query" -> web + "/query?" + rest;
            case "changeset" -> web + "/changeset/" + path(rest);
            case "source", "browser" -> web + "/browser/" + path(rest.replaceFirst("^/", ""));
            case "" -> target.startsWith("/") ? target
                    : target.matches("[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+(?:#\\S+)?") ? web + "/wiki/" + path(target)
                    : null;
            default -> null;
        };
    }

    /** A path in a URL, with its #anchor. */
    private static String path(String value) {
        int hash = value.indexOf('#');
        String path = hash < 0 ? value : value.substring(0, hash);
        StringBuilder out = new StringBuilder();
        for (String part : path.split("/", -1)) {
            if (!out.isEmpty()) {
                out.append('/');
            }
            out.append(URLEncoder.encode(part, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return out + (hash < 0 ? "" : value.substring(hash));
    }

    private String keep(String markdown) {
        kept.add(markdown);
        return START + String.valueOf(kept.size() - 1) + END;
    }

    private String restore(String text) {
        String result = text;
        for (int round = 0; round < 4 && result.indexOf(START) >= 0; round++) {
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
