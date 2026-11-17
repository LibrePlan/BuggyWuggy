/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.mail;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TableBlock;
import org.commonmark.ext.gfm.tables.TableCell;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.Code;
import org.commonmark.node.CustomNode;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Image;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/**
 * Issue descriptions and comments (Markdown) as HTML for mails, with the rules of the web
 * interface (web/src/lib/markdown.ts):
 *
 * <ul>
 * <li>CommonMark with GitHub's tables, strikethrough, task lists and bare URLs as links; a
 * single line break is a line break;</li>
 * <li>HTML in the text is shown as text;</li>
 * <li>links only to http(s) and mailto, and pages of BuggyWuggy itself (made absolute);</li>
 * <li>issue keys of existing projects (BUG-12) link to the issue.</li>
 * </ul>
 *
 * <p>Unlike the web interface, a mail never shows images: an image of the issue's attachments
 * needs a login, and an image from elsewhere would tell its server who read the mail. Each
 * image becomes a link, to the issue or to the image. Task list boxes become ☐ and ☑, as mail
 * clients do not show form fields. The result still goes through {@link HtmlMail#clean}.
 */
public final class IssueMarkdown {

    /** Where links point: the address of BuggyWuggy, the issue, and the existing projects. */
    public record Links(String baseUrl, String issueUrl, Set<String> projectKeys) {
    }

    private static final String PRE = "font-family: monospace; background: #f6f8fa; padding: 8px 12px; "
            + "border-radius: 6px; white-space: pre-wrap;";
    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create(),
            StrikethroughExtension.create(), TaskListItemsExtension.create(), AutolinkExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().extensions(EXTENSIONS)
            .escapeHtml(true)
            .softbreak("<br>\n")
            .attributeProviderFactory(context -> (node, tagName, attributes) -> {
                String style = switch (node) {
                    case Code c -> "font-family: monospace; background: #f6f8fa; padding: 1px 4px; border-radius: 4px;";
                    case FencedCodeBlock f -> tagName.equals("pre") ? PRE : null;
                    case IndentedCodeBlock i -> tagName.equals("pre") ? PRE : null;
                    case BlockQuote q -> "margin: 8px 0; padding: 0 12px; color: #57606a; border-left: 3px solid #d0d7de;";
                    case TableBlock t -> "border-collapse: collapse;";
                    case TableCell c -> "border: 1px solid #d0d7de; padding: 4px 8px;";
                    default -> null;
                };
                if (style != null) {
                    attributes.put("style", style);
                }
            })
            .build();

    static final Pattern ISSUE_KEY = Pattern.compile("\\b[A-Z][A-Z0-9_]{1,9}-[1-9][0-9]*\\b");
    private static final Pattern SAFE = Pattern.compile("^(https?:|mailto:)", Pattern.CASE_INSENSITIVE);

    private IssueMarkdown() {
    }

    /** The HTML of {@code markdown} for a mail. */
    public static String toHtml(String markdown, Links links) {
        Node document = PARSER.parse(markdown);
        // First collect, then change: changing the tree while visiting it skips nodes.
        List<Node> nodes = new ArrayList<>();
        document.accept(new AbstractVisitor() {
            @Override
            public void visit(Link link) {
                nodes.add(link);
                visitChildren(link);
            }

            @Override
            public void visit(Image image) {
                nodes.add(image);
            }

            @Override
            public void visit(Text text) {
                nodes.add(text);
            }

            @Override
            public void visit(CustomNode node) {
                if (node instanceof TaskListItemMarker) {
                    nodes.add(node);
                }
                visitChildren(node);
            }
        });
        for (Node node : nodes) {
            switch (node) {
                case Image image -> replaceImage(image, links);
                case Link link -> fixLink(link, links);
                case Text text -> linkIssueKeys(text, links);
                case TaskListItemMarker marker -> {
                    marker.insertBefore(new Text(marker.isChecked() ? "☑ " : "☐ "));
                    marker.unlink();
                }
                default -> {
                }
            }
        }
        return RENDERER.render(document);
    }

    private static void replaceImage(Image image, Links links) {
        String src = image.getDestination();
        boolean external = SAFE.matcher(src).find() && !src.toLowerCase(Locale.ROOT).startsWith("mailto:");
        Link link = new Link(external ? src : links.issueUrl(), image.getTitle());
        String alt = textOf(image);
        link.appendChild(new Text("[" + (alt.isBlank() ? src : alt) + "]"));
        image.insertBefore(link);
        image.unlink();
    }

    private static void fixLink(Link link, Links links) {
        String href = link.getDestination().strip();
        String lower = href.toLowerCase(Locale.ROOT);
        if (SAFE.matcher(href).find()) {
            return;
        }
        if (lower.startsWith("attachment:")) {
            link.setDestination(links.issueUrl());
        } else if (href.startsWith("/") && !href.startsWith("//")) {
            link.setDestination(links.baseUrl() + href);
        } else {
            // Not allowed (javascript:, data:, //host, relative): keep only the text.
            Node child = link.getFirstChild();
            while (child != null) {
                Node next = child.getNext();
                link.insertBefore(child);
                child = next;
            }
            link.unlink();
        }
    }

    private static void linkIssueKeys(Text text, Links links) {
        for (Node parent = text.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof Link) {
                return;
            }
        }
        String literal = text.getLiteral();
        Matcher m = ISSUE_KEY.matcher(literal);
        int last = 0;
        while (m.find()) {
            String key = m.group();
            if (!links.projectKeys().contains(key.substring(0, key.lastIndexOf('-')))) {
                continue;
            }
            if (m.start() > last) {
                text.insertBefore(new Text(literal.substring(last, m.start())));
            }
            Link link = new Link(links.baseUrl() + "/browse/" + key, null);
            link.appendChild(new Text(key));
            text.insertBefore(link);
            last = m.end();
        }
        if (last > 0) {
            if (last < literal.length()) {
                text.insertBefore(new Text(literal.substring(last)));
            }
            text.unlink();
        }
    }

    private static String textOf(Node node) {
        StringBuilder out = new StringBuilder();
        node.accept(new AbstractVisitor() {
            @Override
            public void visit(Text text) {
                out.append(text.getLiteral());
            }
        });
        return out.toString();
    }
}
