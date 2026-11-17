/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.help;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.buggywuggy.help.HelpDocs.Guide;
import org.junit.jupiter.api.Test;

/** The documentation as rendered for the Help menu, from the real docs/*.md. */
class HelpDocsTest {

    private static final Pattern ID = Pattern.compile(" id=\"([^\"]+)\"");
    private static final Pattern HREF = Pattern.compile(" href=\"([^\"]+)\"");

    private final HelpDocs docs = new HelpDocs("https://example.org/buggywuggy/");

    private Set<String> ids(Guide guide) {
        Set<String> ids = new HashSet<>();
        Matcher m = ID.matcher(docs.page(guide.id()).orElseThrow().html());
        while (m.find()) {
            ids.add(m.group(1));
        }
        return ids;
    }

    @Test
    void everyLinkWithinTheHelpLeadsToAnExistingHeading() {
        for (Guide guide : Guide.values()) {
            var page = docs.page(guide.id()).orElseThrow();
            assertThat(page.headings()).isNotEmpty();
            Matcher m = HREF.matcher(page.html());
            int checked = 0;
            while (m.find()) {
                String href = m.group(1).replace("&amp;", "&");
                if (href.startsWith("#")) {
                    assertThat(ids(guide)).as("%s in %s", href, guide).contains(href.substring(1));
                    checked++;
                } else if (href.startsWith("/help/")) {
                    String[] parts = href.substring("/help/".length()).split("#", 2);
                    Guide other = Guide.byId(parts[0]).orElseThrow();
                    if (parts.length == 2) {
                        assertThat(ids(other)).as("%s in %s", href, guide).contains(parts[1]);
                    }
                    checked++;
                } else {
                    assertThat(href).as("%s in %s", href, guide).startsWith("http").doesNotContain(".md#");
                }
            }
            assertThat(checked).as(guide.name()).isPositive();
        }
    }

    @Test
    void headingsOfLaterFilesGetTheFileNameAsPrefix() {
        var admin = docs.page("admin-guide").orElseThrow();
        assertThat(admin.headings()).extracting(HelpDocs.HelpHeading::id)
                .contains("two-factor-login", "single-sign-on--troubleshooting", "troubleshooting");
        assertThat(admin.html()).contains("<table>");
    }

    @Test
    void rewritesLinks() {
        Map<String, String> first = Map.of("single-sign-on.md", "single-sign-on--single-sign-on-openid-connect");
        Guide admin = Guide.ADMIN_GUIDE;
        assertThat(docs.href(admin, "single-sign-on.md", "#settings", first)).isEqualTo("#single-sign-on--settings");
        assertThat(docs.href(admin, "installation.md", "#e-mail", first)).isEqualTo("#e-mail");
        assertThat(docs.href(admin, "installation.md", "single-sign-on.md", first))
                .isEqualTo("#single-sign-on--single-sign-on-openid-connect");
        assertThat(docs.href(admin, "installation.md", "taskwarrior.md#configuration", first))
                .isEqualTo("/help/user-guide#taskwarrior--configuration");
        assertThat(docs.href(admin, "installation.md", "user-guide.md#the-board", first))
                .isEqualTo("/help/user-guide#the-board");
        assertThat(docs.href(admin, "single-sign-on.md", "domain-model.md", first))
                .isEqualTo("https://example.org/buggywuggy/blob/main/docs/domain-model.md");
        assertThat(docs.href(admin, "installation.md", "../deploy/buggywuggy-deploy", first))
                .isEqualTo("https://example.org/buggywuggy/blob/main/deploy/buggywuggy-deploy");
        assertThat(docs.href(admin, "installation.md", "https://xkcd.com/", first)).isEqualTo("https://xkcd.com/");
    }

    @Test
    void escapesHtmlInTheDocumentation() {
        assertThat(docs.page("admin-guide").orElseThrow().html()).contains("&lt;tenant-id&gt;")
                .doesNotContain("<tenant-id>");
    }

    @Test
    void theLicenseIsPlainText() {
        var license = docs.page("license").orElseThrow();
        assertThat(license.html()).startsWith("<pre").contains("GNU AFFERO GENERAL PUBLIC LICENSE");
        assertThat(docs.page("nope")).isEmpty();
    }
}
