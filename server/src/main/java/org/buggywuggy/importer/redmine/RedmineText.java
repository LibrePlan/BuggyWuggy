/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.redmine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts the texts of Redmine to Markdown. Redmine formats them with Textile or with
 * Markdown (CommonMark), as its administrator chose; the API does not tell which, so
 * {@link #looksLikeTextile} guesses it from the texts of a project.
 * <ul>
 * <li>Textile: {@code <pre>} blocks, headings, quotes, lists, tables, text effects, links
 * ({@code "text":url}), images ({@code !file.png!}) and inline code ({@code @code@});</li>
 * <li>both: Redmine's own links: {@code attachment:file.txt}, images and links to attachments
 * by file name, wiki links ({@code [[Page]]}) and {@code ##12} (an issue with its title).</li>
 * </ul>
 * Issue references ({@code #12}) stay; the import engine rewrites them.
 */
final class RedmineText {

    /** What a text refers to outside itself. */
    interface Context {

        /** The link to an attachment of the issue by file name, or null when there is none. */
        String attachment(String fileName);

        /** The web address of a wiki page; {@code project} null for the project of the issue. */
        String wiki(String project, String page);
    }

    // --- guessing the format ---------------------------------------------------------------

    private static final List<Pattern> TEXTILE = List.of(
            Pattern.compile("(?m)^h[1-6](?:\\([^)]*\\)|\\{[^}]*\\})?\\.\\s"),
            Pattern.compile("(?m)^bq\\.\\s"),
            Pattern.compile("<pre>"),
            Pattern.compile("\"[^\"\\n]+\":(?:https?://|/|attachment:)"),
            Pattern.compile("(?m)^\\|_\\."),
            Pattern.compile("(?<![\\w\\]])![^\\s!(\\[][^!\\n]*\\.(?i:png|jpe?g|gif|svg|webp|bmp)(?:\\([^)]*\\))?!"));
    private static final List<Pattern> MARKDOWN = List.of(
            Pattern.compile("(?m)^ {0,3}(?:```|~~~)"),
            Pattern.compile("(?<!!)\\[[^\\]\\n]+\\]\\([^)\\s]+(?:\\s+\"[^\"]*\")?\\)"),
            Pattern.compile("!\\[[^\\]\\n]*\\]\\([^)\\s]+\\)"),
            Pattern.compile("(?m)^\\|.*\\|\\s*\\n\\|\\s*:?-{3,}"),
            Pattern.compile("(?m)^#{1,6}[^\\s#]"),
            Pattern.compile("`[^`\\n]+`"));

    /** Whether these texts look written in Textile rather than Markdown (or plain text). */
    static boolean looksLikeTextile(Collection<String> texts) {
        int textile = 0;
        int markdown = 0;
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            textile += (int) TEXTILE.stream().filter(p -> p.matcher(text).find()).count();
            markdown += (int) MARKDOWN.stream().filter(p -> p.matcher(text).find()).count();
        }
        return textile > markdown;
    }

    // --- both formats ----------------------------------------------------------------------

    private static final Pattern ATTACHMENT_LINK = Pattern.compile(
            "(?<![\\w/])attachment:(?:\"([^\"\\n]+)\"|([^\\s\"<>()\\[\\]]+[^\\s\"<>()\\[\\].,;:!?]))");
    private static final Pattern WIKI = Pattern.compile("\\[\\[([^\\[\\]|\\n]+)(?:\\|([^\\[\\]\\n]+))?\\]\\]");
    private static final Pattern ISSUE_WITH_TITLE = Pattern.compile("(?<![\\w#&])#{2,3}(\\d+)\\b");
    private static final Pattern TOC = Pattern.compile("\\{\\{[<>]?toc\\}\\}");
    private static final Pattern TOC_LINE = Pattern.compile("(?m)^[ \\t]*\\{\\{[<>]?toc\\}\\}[ \\t]*(?:\\n|$)");

    // --- Markdown ----------------------------------------------------------------------------

    private static final Pattern MD_CODE = Pattern.compile("(?ms)^( {0,3})(```|~~~).*?^ {0,3}\\2[^\\n]*$|`[^`\\n]+`");
    /** Links and images whose target is a file name: Redmine shows the attachment of that name. */
    private static final Pattern MD_FILE_LINK = Pattern.compile("(!?\\[[^\\]\\n]*\\]\\()([^)\\s/:#?]+)(\\s+\"[^\"]*\")?\\)");

    // --- Textile ---------------------------------------------------------------------------

    private static final Pattern PRE = Pattern.compile(
            "(?s)<pre>\\s*(?:<code(?:\\s+class=\"([^\"]*)\")?>)?(.*?)(?:</code>\\s*)?</pre>"
                    + "|<notextile>(.*?)</notextile>");
    private static final String ATTRIBUTES = "(?:\\([^)]*\\)|\\{[^}]*\\}|\\[[^\\]]*\\]|[<>=])*";
    private static final Pattern HEADING = Pattern.compile("^h([1-6])" + ATTRIBUTES + "\\.\\s+(.*)$");
    private static final Pattern BLOCKQUOTE = Pattern.compile("^bq" + ATTRIBUTES + "\\.\\s+(.*)$");
    private static final Pattern PARAGRAPH = Pattern.compile("^p" + ATTRIBUTES + "\\.\\s+(.*)$");
    private static final Pattern CODE_LINE = Pattern.compile("^(?:bc|pre)" + ATTRIBUTES + "\\.\\s+(.*)$");
    private static final Pattern TABLE_ATTRIBUTES = Pattern.compile("^table" + ATTRIBUTES + "\\.\\s*$");
    private static final Pattern LIST = Pattern.compile("^\\s*([*#]+)\\s+(.*)$");
    private static final Pattern CELL_ATTRIBUTES = Pattern.compile("^((?:_|\\\\\\d+|/\\d+|[<>=^~]|\\{[^}]*\\}|"
            + "\\([^)]*\\))+)\\.\\s*");
    private static final Pattern RAW = Pattern.compile("==(.+?)==");
    private static final Pattern HTML_CODE = Pattern.compile("<code>(.+?)</code>");
    private static final Pattern CODE = Pattern.compile("(?<![\\w@])@(?=\\S)([^@\\n]+?)(?<=\\S)@(?![\\w@])");
    private static final Pattern LINK = Pattern.compile("\\[?\"([^\"\\n]+?)\":((?:https?|ftp)://[^\\s<>\"|\\]]+"
            + "|mailto:[^\\s<>\"|\\]]+|/[^\\s<>\"|\\]]*|attachment:[^\\s<>\"|\\]]+)\\]?");
    private static final Pattern IMAGE = Pattern.compile("!" + ATTRIBUTES
            + "([^\\s!()\\[\\]][^!\\n()]*?)(?:\\(([^)\\n]*)\\))?!(?::([^\\s<>\"]+))?");
    private static final Pattern SPAN = Pattern.compile("%(?:\\{[^}]*\\}|\\([^)]*\\))*(.+?)%");
    private static final Pattern BOLD = Pattern.compile("(?<![\\w*])\\*(?=\\S)([^*\\n]+?)(?<=\\S)\\*(?![\\w*])");
    private static final Pattern DOUBLE_ITALIC = Pattern.compile("(?<![\\w_])__(?=\\S)([^_\\n]+?)(?<=\\S)__(?![\\w_])");
    private static final Pattern ITALIC = Pattern.compile("(?<![\\w_])_(?=\\S)([^_\\n]+?)(?<=\\S)_(?![\\w_])");
    private static final Pattern STRIKE = Pattern.compile("(?<![\\w-])-(?=\\S)([^-\\n]+?)(?<=\\S)-(?![\\w-])");
    private static final Pattern INSERTED = Pattern.compile("(?<![\\w+])\\+(?=\\S)([^+\\n]+?)(?<=\\S)\\+(?![\\w+])");
    private static final Pattern CITATION = Pattern.compile("\\?\\?(?=\\S)([^?\\n]+?)(?<=\\S)\\?\\?");
    private static final Pattern TRAILING = Pattern.compile("[.,;:!?)]+$");

    private static final char START = '\u0001';
    private static final char END = '\u0002';

    private final Context context;
    /** Parts that are finished and must not be converted again (code, links). */
    private final List<String> kept = new ArrayList<>();

    private RedmineText(Context context) {
        this.context = context;
    }

    static String toMarkdown(String text, boolean textile, Context context) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String normalized = TOC_LINE.matcher(text.replace("\r\n", "\n").replace('\r', '\n')).replaceAll("");
        RedmineText converter = new RedmineText(context);
        return converter.restore(textile ? converter.textile(normalized) : converter.markdown(normalized));
    }

    // --- Markdown ----------------------------------------------------------------------------

    private String markdown(String text) {
        StringBuilder out = new StringBuilder();
        Matcher code = MD_CODE.matcher(text);
        int last = 0;
        while (code.find()) {
            out.append(markdownProse(text.substring(last, code.start()))).append(code.group());
            last = code.end();
        }
        return out.append(markdownProse(text.substring(last))).toString();
    }

    private String markdownProse(String text) {
        String result = MD_FILE_LINK.matcher(text).replaceAll(m -> {
            String url = context.attachment(m.group(2));
            return Matcher.quoteReplacement(url == null ? m.group() : keep(m.group(1) + url
                    + (m.group(3) == null ? "" : m.group(3)) + ")"));
        });
        return redmineLinks(result);
    }

    // --- shared --------------------------------------------------------------------------

    /** attachment:file, [[Wiki]], ##12, {{toc}}. */
    private String redmineLinks(String text) {
        String result = TOC.matcher(text).replaceAll("");
        result = ATTACHMENT_LINK.matcher(result).replaceAll(m -> {
            String file = m.group(1) != null ? m.group(1) : m.group(2);
            String url = context.attachment(file);
            return Matcher.quoteReplacement(url == null ? file : keep("[" + file + "](" + url + ")"));
        });
        result = WIKI.matcher(result).replaceAll(m -> {
            String target = m.group(1).strip();
            String project = null;
            int colon = target.indexOf(':');
            if (colon > 0 && target.substring(0, colon).matches(RedmineConnector.IDENTIFIER)) {
                project = target.substring(0, colon);
                target = target.substring(colon + 1);
            }
            String shown = m.group(2) != null ? m.group(2).strip() : target;
            String url = target.isBlank() ? null : context.wiki(project, target);
            return Matcher.quoteReplacement(url == null ? shown : keep("[" + shown + "](" + url + ")"));
        });
        return ISSUE_WITH_TITLE.matcher(result).replaceAll("#$1");
    }

    // --- Textile ---------------------------------------------------------------------------

    private String textile(String text) {
        StringBuilder out = new StringBuilder();
        Matcher pre = PRE.matcher(text);
        int last = 0;
        while (pre.find()) {
            out.append(blocks(text.substring(last, pre.start())));
            if (pre.group(3) != null) {
                // <notextile>: as it is.
                out.append(keep(pre.group(3)));
                last = pre.end();
                continue;
            }
            if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
            String content = pre.group(2).replaceAll("^\\n+", "").replaceAll("\\s+$", "");
            out.append(keep(fence(content, language(pre.group(1)))));
            last = pre.end();
            if (last < text.length() && text.charAt(last) != '\n') {
                out.append('\n');
            }
        }
        return out.append(blocks(text.substring(last))).toString();
    }

    private static String fence(String content, String language) {
        String fence = content.contains("```") ? "~~~" : "```";
        return fence + language + "\n" + content + "\n" + fence;
    }

    /** class="ruby", "language-ruby", "ruby syntaxhl" → ruby */
    private static String language(String classes) {
        if (classes == null) {
            return "";
        }
        for (String part : classes.strip().split("\\s+")) {
            String value = part.startsWith("language-") ? part.substring("language-".length()) : part;
            if (value.matches("[\\w+#.-]+") && !value.equals("syntaxhl")) {
                return value.toLowerCase(Locale.ROOT);
            }
        }
        return "";
    }

    private String blocks(String text) {
        String[] lines = text.split("\n", -1);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher m;
            if (line.startsWith("|") || TABLE_ATTRIBUTES.matcher(line).matches()
                    && i + 1 < lines.length && lines[i + 1].startsWith("|")) {
                int end = i;
                if (!line.startsWith("|")) {
                    end++;
                }
                List<String> rows = new ArrayList<>();
                while (end < lines.length && lines[end].startsWith("|")) {
                    rows.add(lines[end++]);
                }
                out.addAll(table(rows));
                i = end - 1;
            } else if ((m = HEADING.matcher(line)).matches()) {
                out.add("#".repeat(Integer.parseInt(m.group(1))) + " " + inline(m.group(2)));
            } else if ((m = BLOCKQUOTE.matcher(line)).matches()) {
                out.add("> " + inline(m.group(1)));
            } else if ((m = CODE_LINE.matcher(line)).matches()) {
                out.add(keep(fence(m.group(1), "")));
            } else if ((m = PARAGRAPH.matcher(line)).matches()) {
                out.add(inline(m.group(1)));
            } else if ((m = LIST.matcher(line)).matches()) {
                out.add(listItem(m.group(1), m.group(2)));
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

    /** |_. Name |_. Value | and |a|b| rows; Markdown needs a header, so the first row is one. */
    private List<String> table(List<String> rows) {
        List<String> out = new ArrayList<>();
        for (String row : rows) {
            String inner = atoms(row.strip()).substring(1);
            if (inner.endsWith("|")) {
                inner = inner.substring(0, inner.length() - 1);
            }
            List<String> cells = new ArrayList<>();
            for (String cell : inner.split("\\|", -1)) {
                Matcher attributes = CELL_ATTRIBUTES.matcher(cell.strip());
                String content = attributes.lookingAt() ? cell.strip().substring(attributes.end()) : cell;
                cells.add(restore(effects(content.strip())).replace("|", "\\|"));
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

    /** What must not be touched by text effects: code, links, images, Redmine's links. */
    private String atoms(String text) {
        String result = RAW.matcher(text).replaceAll(m -> Matcher.quoteReplacement(keep(m.group(1))));
        result = HTML_CODE.matcher(result).replaceAll(m -> Matcher.quoteReplacement(keep(code(m.group(1)))));
        result = CODE.matcher(result).replaceAll(m -> Matcher.quoteReplacement(keep(code(m.group(1)))));
        result = LINK.matcher(result).replaceAll(m -> Matcher.quoteReplacement(link(m)));
        result = IMAGE.matcher(result).replaceAll(m -> Matcher.quoteReplacement(image(m)));
        return redmineLinks(result);
    }

    private String effects(String text) {
        String result = SPAN.matcher(text).replaceAll("$1");
        result = BOLD.matcher(result).replaceAll("**$1**");
        result = DOUBLE_ITALIC.matcher(result).replaceAll("*$1*");
        result = ITALIC.matcher(result).replaceAll("*$1*");
        result = STRIKE.matcher(result).replaceAll("~~$1~~");
        result = INSERTED.matcher(result).replaceAll("$1");
        return CITATION.matcher(result).replaceAll("*$1*");
    }

    private static String code(String content) {
        return content.contains("`") ? "`` " + content + " ``" : "`" + content + "`";
    }

    /** "text":url, "text(title)":url; punctuation at the end belongs to the sentence. */
    private String link(java.util.regex.MatchResult m) {
        String text = m.group(1).replaceAll("\\s*\\([^)]*\\)$", "");
        String url = m.group(2);
        String after = "";
        Matcher trailing = TRAILING.matcher(url);
        if (trailing.find() && !m.group().endsWith("]")) {
            after = url.substring(trailing.start());
            url = url.substring(0, trailing.start());
        }
        if (url.startsWith("attachment:")) {
            String found = context.attachment(url.substring("attachment:".length()));
            if (found == null) {
                return text + after;
            }
            url = found;
        }
        return keep("[" + text + "](" + url + ")") + after;
    }

    /** !file.png!, !>file.png(alt)!, !https://…/x.png!:https://… */
    private String image(java.util.regex.MatchResult m) {
        String source = m.group(1).strip();
        String alt = m.group(2) == null ? "" : m.group(2).strip();
        String url = source.matches("(?i)(https?://|/).+") ? source : context.attachment(source);
        if (url == null) {
            return m.group();
        }
        String image = "![" + (alt.isEmpty() && !source.contains("/") ? source : alt) + "](" + url + ")";
        return keep(m.group(3) == null ? image : "[" + image + "](" + m.group(3) + ")");
    }

    private String keep(String markdown) {
        kept.add(markdown);
        return START + String.valueOf(kept.size() - 1) + END;
    }

    private String restore(String text) {
        String result = text;
        // Kept parts may contain kept parts; repeat until none are left.
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
