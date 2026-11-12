/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.mail;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.safety.Safelist;
import org.jsoup.select.NodeVisitor;

/**
 * Turns the HTML of a rendered template into a safe mail: scripts, forms, event handlers and
 * {@code javascript:} links are removed (administrators write the HTML, but mail clients and
 * the preview should never run anything), and a plain-text alternative is derived from it.
 */
public final class HtmlMail {

    private static final Safelist SAFELIST = Safelist.relaxed()
            .addTags("hr", "center", "font", "s", "del", "ins", "mark", "small", "big")
            .addAttributes(":all", "style", "align", "valign", "width", "height", "bgcolor", "dir", "title")
            .addAttributes("table", "border", "cellpadding", "cellspacing")
            .addAttributes("font", "color", "face", "size")
            .addProtocols("img", "src", "http", "https", "cid", "data")
            .preserveRelativeLinks(false);

    private HtmlMail() {
    }

    /** The cleaned body, wrapped in a complete HTML document. */
    public static String document(String bodyHtml) {
        String clean = clean(bodyHtml);
        return """
                <!DOCTYPE html>
                <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"></head>
                <body style="font-family: -apple-system, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; \
                font-size: 14px; line-height: 1.5; color: #1f2328;">
                """ + clean + "\n</body></html>\n";
    }

    /** Removes everything that is not safe in a mail. */
    public static String clean(String html) {
        Document.OutputSettings settings = new Document.OutputSettings().prettyPrint(false);
        return Jsoup.clean(html, "", SAFELIST, settings);
    }

    /** A plain-text version for mail clients that do not show HTML. */
    public static String text(String html) {
        Document doc = Jsoup.parse(html);
        StringBuilder out = new StringBuilder();
        doc.body().traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
                if (node instanceof TextNode text) {
                    String value = text.text().replaceAll("\\s+", " ");
                    if (out.isEmpty() || out.charAt(out.length() - 1) == '\n') {
                        value = value.stripLeading();
                    }
                    out.append(value);
                } else if (node instanceof Element e) {
                    switch (e.normalName()) {
                        case "br" -> out.append('\n');
                        case "li" -> newLine(out).append("- ");
                        case "td", "th" -> {
                            if (e.elementSiblingIndex() > 0) {
                                out.append("  ");
                            }
                        }
                        case "hr" -> newLine(out).append("----------------------------------------\n");
                        default -> {
                            if (isBlock(e)) {
                                newLine(out);
                            }
                        }
                    }
                }
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element e) {
                    if (e.normalName().equals("a")) {
                        String href = e.attr("href");
                        if (!href.isBlank() && !href.startsWith("mailto:") && !href.equals(e.text().strip())) {
                            out.append(" <").append(href).append('>');
                        }
                    } else if (isBlock(e)) {
                        newLine(out);
                        if (e.normalName().matches("p|h[1-6]|table|ul|ol|blockquote")) {
                            out.append('\n');
                        }
                    }
                }
            }
        });
        return out.toString()
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .strip() + "\n";
    }

    private static StringBuilder newLine(StringBuilder out) {
        if (!out.isEmpty() && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
        return out;
    }

    private static boolean isBlock(Element e) {
        return switch (e.normalName()) {
            case "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "tr", "table", "ul", "ol", "blockquote", "pre",
                    "center", "section", "header", "footer" -> true;
            default -> false;
        };
    }
}
