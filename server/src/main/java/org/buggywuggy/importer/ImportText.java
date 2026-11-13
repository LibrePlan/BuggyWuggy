/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites imported Markdown for BuggyWuggy. Outside code:
 * <ul>
 * <li>{@code #12}, {@code owner/repo#12} (or, for Jira, {@code ABC-12}) and bare links to issues
 * of the source become the key of the imported issue, or a link to the original (an issue or
 * pull request that was not imported);</li>
 * <li>links to attachments point to the copied attachment, or to the original file when it was
 * not copied;</li>
 * <li>other relative links become absolute links to the source's web address.</li>
 * </ul>
 */
public final class ImportText {

    /** Looks up what an import created. */
    public interface Resolver {

        /** The key of the imported issue; {@code repository} is null for this repository. */
        Optional<String> issueKey(String repository, long number);

        /** The copied attachment for the key the source's attachment pattern found. */
        Optional<Long> attachmentId(String key);

        /** The web address of an issue that was not imported. */
        String issueUrl(String repository, long number);

        /** The original of an attachment link that was not copied. */
        String attachmentUrl(String found);
    }

    private static final Pattern CODE = Pattern.compile("(?ms)^( {0,3})(```|~~~).*?^ {0,3}\\2[^\\n]*$|`[^`\\n]+`");
    /** Links and images, autolinks and bare URLs: references inside them are not rewritten. */
    private static final Pattern PROTECTED = Pattern.compile("!?\\[[^\\]\\n]*\\]\\([^)\\s]*(?:\\s+\"[^\"]*\")?\\)"
            + "|<https?://[^>\\s]+>|https?://[^\\s)<>\\]]+");
    private static final Pattern CROSS_REF =
            Pattern.compile("(?<![\\w/.\\-])([\\w.\\-]+(?:/[\\w.\\-]+)+)#(\\d+)\\b");
    private static final Pattern LOCAL_REF = Pattern.compile("(?<![\\w&/#\\]])#(\\d+)\\b");
    private static final Pattern TRAILING = Pattern.compile("[.,;:!?]+$");
    private static final Pattern RELATIVE_LINK = Pattern.compile("(\\]\\(|src=\"|href=\")/(?!api/v1/attachments/)");

    private final String web;
    private final String repository;
    private final Resolver resolver;
    private final Pattern attachment;
    private final Pattern issueUrl;
    private final Pattern keys;

    /**
     * @param web         the web address issues are under, e.g. https://github.com
     * @param repository  the imported project, e.g. owner/repo
     * @param attachments attachment links with the key as group 1, or null
     */
    public ImportText(String web, String repository, Pattern attachments, Resolver resolver) {
        this(web, repository, attachments, defaultIssueLinks(web), null, resolver);
    }

    /**
     * @param issueLinks links to issues, with the repository as group 1 and the number as group 2
     * @param keys       references like {@code ABC-12} (project as group 1, number as group 2)
     *                   instead of {@code #12} and {@code owner/repo#12}; null for those
     */
    public ImportText(String web, String repository, Pattern attachments, Pattern issueLinks, Pattern keys,
            Resolver resolver) {
        this.web = web;
        this.repository = repository;
        this.resolver = resolver;
        this.attachment = attachments;
        this.issueUrl = issueLinks;
        this.keys = keys;
    }

    /** GitHub and Forgejo: /owner/repo/issues/12; GitLab: /group/project/-/issues/12 */
    public static Pattern defaultIssueLinks(String web) {
        return Pattern.compile(Pattern.quote(web) + "/([\\w.\\-]+(?:/[\\w.\\-]+)+?)/(?:-/)?issues/(\\d+)/?");
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
            String match = link.group();
            // Like Markdown autolinks: punctuation at the end of a bare URL is not part of it.
            Matcher trailing = TRAILING.matcher(match);
            String found = match.startsWith("http") && trailing.find() ? match.substring(0, trailing.start()) : match;
            Matcher issue = issueUrl.matcher(found);
            Optional<String> key = issue.matches() ? key(issue.group(1), Long.parseLong(issue.group(2)))
                    : Optional.empty();
            out.append(key.orElseGet(() -> links(found)));
            out.append(match.substring(found.length()));
            last = link.end();
        }
        out.append(references(links(text.substring(last))));
        return out.toString();
    }

    private Optional<String> key(String repo, long number) {
        // No repository in the link (Redmine: /issues/12): this one.
        return repo == null || repo.isEmpty() || repo.equalsIgnoreCase(repository) ? resolver.issueKey(null, number) : resolver.issueKey(repo, number);
    }

    /** Attachment and relative links. */
    private String links(String text) {
        String result = attachment == null ? text : attachment.matcher(text).replaceAll(m -> Matcher.quoteReplacement(
                resolver.attachmentId(m.group(1))
                        .map(id -> "/api/v1/attachments/" + id + "/content")
                        .orElseGet(() -> resolver.attachmentUrl(m.group()))));
        return RELATIVE_LINK.matcher(result).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + web + "/"));
    }

    private String references(String text) {
        if (keys != null) {
            // Only keys of this project, or of issues that were imported: ABC-12 may also be UTF-8.
            return keys.matcher(text).replaceAll(m -> {
                String repo = m.group(1);
                long number = Long.parseLong(m.group(2));
                return Matcher.quoteReplacement(key(repo, number).orElseGet(() -> repo.equals(repository)
                        ? "[" + m.group() + "](" + resolver.issueUrl(repo, number) + ")" : m.group()));
            });
        }
        String result = CROSS_REF.matcher(text).replaceAll(m -> {
            String repo = m.group(1);
            long number = Long.parseLong(m.group(2));
            return Matcher.quoteReplacement(key(repo, number).orElseGet(() ->
                    "[" + repo + "#" + number + "](" + resolver.issueUrl(repo, number) + ")"));
        });
        return LOCAL_REF.matcher(result).replaceAll(m -> {
            long number = Long.parseLong(m.group(1));
            return Matcher.quoteReplacement(resolver.issueKey(null, number).orElseGet(() ->
                    "[#" + number + "](" + resolver.issueUrl(repository, number) + ")"));
        });
    }
}
