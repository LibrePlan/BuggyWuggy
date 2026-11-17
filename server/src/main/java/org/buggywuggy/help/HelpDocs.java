/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.help;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.heading.anchor.IdGenerator;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

/**
 * The documentation in {@code docs/*.md} as HTML for the Help menu. The Markdown files are
 * copied into the jar at {@code help/} by the build and rendered once, at their first request.
 *
 * <p>A guide can consist of several files; the headings of the second and later files get
 * the file name as prefix ({@code single-sign-on--troubleshooting}), so that equal headings in
 * different files stay apart. Links are rewritten to match: {@code #anchor} and
 * {@code other.md#anchor} lead to the right place in the same or the other guide; links to
 * other files of the repository (configuration examples, documentation not shown here) lead to
 * the source code repository.
 */
@Component
public class HelpDocs {

    /** The guides of the Help menu, each made of one or more files of {@code docs/}. */
    public enum Guide {
        USER_GUIDE("user-guide", "User guide", List.of("user-guide.md", "taskwarrior.md")),
        ADMIN_GUIDE("admin-guide", "Administrator guide", List.of("installation.md", "single-sign-on.md"));

        private final String id;
        private final String title;
        private final List<String> files;

        Guide(String id, String title, List<String> files) {
            this.id = id;
            this.title = title;
            this.files = files;
        }

        public String id() {
            return id;
        }

        static Optional<Guide> byId(String id) {
            for (Guide g : values()) {
                if (g.id.equals(id)) {
                    return Optional.of(g);
                }
            }
            return Optional.empty();
        }

        static Optional<Guide> containing(String file) {
            for (Guide g : values()) {
                if (g.files.contains(file)) {
                    return Optional.of(g);
                }
            }
            return Optional.empty();
        }
    }

    /** A heading for the table of contents; {@code id} is the anchor in {@code html}. */
    public record HelpHeading(int level, String id, String text) {
    }

    /** A help page: the HTML of the body (no {@code <html>} or {@code <body>}) and its headings. */
    public record HelpPage(String id, String title, String html, List<HelpHeading> headings) {
    }

    public static final String LICENSE = "license";

    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create());
    private static final Pattern DOC_LINK = Pattern.compile("^([a-z0-9-]+\\.md)(?:#(.*))?$");

    private final String sourceUrl;
    private final Map<String, HelpPage> pages = new ConcurrentHashMap<>();

    public HelpDocs(@Value("${buggywuggy.source-url}") String sourceUrl) {
        this.sourceUrl = sourceUrl.endsWith("/") ? sourceUrl.substring(0, sourceUrl.length() - 1) : sourceUrl;
    }

    /** The page with this id ({@link Guide#id()} or {@link #LICENSE}); empty if there is none. */
    public Optional<HelpPage> page(String id) {
        if (LICENSE.equals(id)) {
            return Optional.of(pages.computeIfAbsent(id, i -> license()));
        }
        return Guide.byId(id).map(g -> pages.computeIfAbsent(id, i -> render(g)));
    }

    private HelpPage license() {
        String text = read("LICENSE");
        return new HelpPage(LICENSE, "GNU Affero General Public License",
                "<pre class=\"help-license\">" + HtmlUtils.htmlEscape(text) + "</pre>", List.of());
    }

    private HelpPage render(Guide guide) {
        Parser parser = Parser.builder().extensions(EXTENSIONS).build();
        // First all files, so that links between them can point at the first heading of a file.
        Map<String, Node> documents = new LinkedHashMap<>();
        Map<Node, String> ids = new IdentityHashMap<>();
        Map<String, String> firstIds = new HashMap<>();
        List<HelpHeading> headings = new ArrayList<>();
        for (String file : guide.files) {
            Node document = parser.parse(read(file));
            documents.put(file, document);
            IdGenerator generator = IdGenerator.builder().prefix(prefix(guide, file)).build();
            document.accept(new AbstractVisitor() {
                @Override
                public void visit(org.commonmark.node.Heading heading) {
                    String text = text(heading);
                    String id = generator.generateId(text);
                    ids.put(heading, id);
                    firstIds.putIfAbsent(file, id);
                    headings.add(new HelpHeading(heading.getLevel(), id, text));
                }
            });
        }
        StringBuilder html = new StringBuilder();
        for (var entry : documents.entrySet()) {
            String file = entry.getKey();
            HtmlRenderer renderer = HtmlRenderer.builder().extensions(EXTENSIONS).escapeHtml(true)
                    .attributeProviderFactory(context -> (node, tagName, attributes) -> {
                        if (node instanceof org.commonmark.node.Heading && ids.containsKey(node)) {
                            attributes.put("id", ids.get(node));
                        } else if (node instanceof Link link) {
                            String href = href(guide, file, link.getDestination(), firstIds);
                            attributes.put("href", href);
                            if (href.startsWith("http")) {
                                attributes.put("target", "_blank");
                                attributes.put("rel", "noopener noreferrer");
                            }
                        }
                    })
                    .build();
            html.append("<section>").append(renderer.render(entry.getValue())).append("</section>\n");
        }
        return new HelpPage(guide.id, guide.title, html.toString(), List.copyOf(headings));
    }

    /** Where a link in {@code file} of {@code guide} should lead in the Help pages. */
    String href(Guide guide, String file, String destination, Map<String, String> firstIds) {
        if (destination.startsWith("#")) {
            return "#" + prefix(guide, file) + destination.substring(1);
        }
        if (URI.create(destination.replace(" ", "%20")).isAbsolute() || destination.startsWith("/")) {
            return destination;
        }
        Matcher doc = DOC_LINK.matcher(destination);
        if (doc.matches()) {
            String target = doc.group(1);
            String anchor = doc.group(2);
            Optional<Guide> other = Guide.containing(target);
            if (other.isPresent()) {
                String fragment = anchor != null ? prefix(other.get(), target) + anchor
                        : other.get() == guide ? firstIds.get(target) : "";
                String page = other.get() == guide ? "" : "/help/" + other.get().id;
                return fragment == null || fragment.isEmpty() ? (page.isEmpty() ? "#" : page) : page + "#" + fragment;
            }
        }
        // Another file of the repository: show it in the source code repository.
        return URI.create(sourceUrl + "/blob/main/docs/").resolve(destination).toString();
    }

    /** The prefix of the heading ids of {@code file}: none for the first file of a guide. */
    static String prefix(Guide guide, String file) {
        return guide.files.indexOf(file) == 0 ? "" : file.substring(0, file.length() - ".md".length()) + "--";
    }

    private static String text(Node node) {
        StringBuilder text = new StringBuilder();
        node.accept(new AbstractVisitor() {
            @Override
            public void visit(Text t) {
                text.append(t.getLiteral());
            }

            @Override
            public void visit(Code code) {
                text.append(code.getLiteral());
            }
        });
        return text.toString();
    }

    private static String read(String file) {
        try (InputStream in = HelpDocs.class.getResourceAsStream("/help/" + file)) {
            if (in == null) {
                throw new IllegalStateException("help/" + file + " is missing from the build");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
