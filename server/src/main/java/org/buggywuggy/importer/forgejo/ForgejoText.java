/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.forgejo;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites Forgejo Markdown for BuggyWuggy. Outside code:
 * <ul>
 * <li>{@code #12} and {@code owner/repo#12} become the key of the imported issue, or a link to
 * the original (an issue or pull request that was not imported);</li>
 * <li>links to attachments ({@code /attachments/<uuid>}) point to the copied attachment, or to
 * the original file when it was not copied;</li>
 * <li>other relative links become absolute links to the Forgejo server.</li>
 * </ul>
 */
public final class ForgejoText {

    /** Looks up what an import created. */
    public interface Resolver {

        /** The key of the imported issue; {@code repository} is null for this repository. */
        Optional<String> issueKey(String repository, long number);

        Optional<Long> attachmentId(String uuid);
    }

    private static final Pattern CODE = Pattern.compile("(?ms)^( {0,3})(```|~~~).*?^ {0,3}\\2[^\\n]*$|`[^`\\n]+`");
    /** Links and images, autolinks and bare URLs: references inside them are not rewritten. */
    private static final Pattern PROTECTED = Pattern.compile("!?\\[[^\\]\\n]*\\]\\([^)\\s]*(?:\\s+\"[^\"]*\")?\\)"
            + "|<https?://[^>\\s]+>|https?://[^\\s)<>\\]]+");
    private static final Pattern CROSS_REF = Pattern.compile("(?<![\\w/.\\-])([\\w.\\-]+/[\\w.\\-]+)#(\\d+)\\b");
    private static final Pattern LOCAL_REF = Pattern.compile("(?<![\\w&/#\\]])#(\\d+)\\b");
    private static final Pattern RELATIVE_LINK = Pattern.compile("(\\]\\(|src=\"|href=\")/(?!api/v1/attachments/)");

    private final String base;
    private final String repository;
    private final Resolver resolver;
    private final Pattern attachment;

    public ForgejoText(String base, String repository, Resolver resolver) {
        this.base = base;
        this.repository = repository;
        this.resolver = resolver;
        this.attachment = Pattern.compile("(?:" + Pattern.quote(base) + ")?/attachments/([0-9a-fA-F-]{36})\\b");
    }

    public String rewrite(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder();
        Matcher code = CODE.matcher(text);
        int last = 0;
        while (code.find()) {
            out.append(prose(text.substring(last, code.start())));
            out.append(code.group());
            last = code.end();
        }
        out.append(prose(text.substring(last)));
        return out.toString();
    }

    private String prose(String text) {
        StringBuilder out = new StringBuilder();
        Matcher link = PROTECTED.matcher(text);
        int last = 0;
        while (link.find()) {
            out.append(references(links(text.substring(last, link.start()))));
            out.append(links(link.group()));
            last = link.end();
        }
        out.append(references(links(text.substring(last))));
        return out.toString();
    }

    /** Attachment and relative links. */
    private String links(String text) {
        String result = attachment.matcher(text).replaceAll(m -> Matcher.quoteReplacement(
                resolver.attachmentId(m.group(1).toLowerCase(java.util.Locale.ROOT))
                        .map(id -> "/api/v1/attachments/" + id + "/content")
                        .orElse(base + "/attachments/" + m.group(1))));
        return RELATIVE_LINK.matcher(result).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + base + "/"));
    }

    private String references(String text) {
        String result = CROSS_REF.matcher(text).replaceAll(m -> {
            String repo = m.group(1);
            long number = Long.parseLong(m.group(2));
            Optional<String> key = repo.equalsIgnoreCase(repository) ? resolver.issueKey(null, number)
                    : resolver.issueKey(repo, number);
            return Matcher.quoteReplacement(key.orElseGet(() ->
                    "[" + repo + "#" + number + "](" + base + "/" + repo + "/issues/" + number + ")"));
        });
        return LOCAL_REF.matcher(result).replaceAll(m -> {
            long number = Long.parseLong(m.group(1));
            return Matcher.quoteReplacement(resolver.issueKey(null, number).orElseGet(() ->
                    "[#" + number + "](" + base + "/" + repository + "/issues/" + number + ")"));
        });
    }
}
