/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class IssueMarkdownTest {

    private static final IssueMarkdown.Links LINKS = new IssueMarkdown.Links("https://bugs.example.org",
            "https://bugs.example.org/browse/BUG-1", Set.of("BUG"));

    private static String html(String markdown) {
        return HtmlMail.clean(IssueMarkdown.toHtml(markdown, LINKS));
    }

    @Test
    void keepsLineBreaksOfPlainText() {
        assertThat(IssueMarkdown.toHtml("first\nsecond", LINKS)).isEqualTo("<p>first<br>\nsecond</p>\n");
    }

    @Test
    void formatsGitHubFlavouredMarkdown() {
        String html = html("**bold** _it_ ~~gone~~ `code`\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n```\nx < y\n```");
        assertThat(html).contains("<strong>bold</strong>", "<em>it</em>", "<del>gone</del>", "<table",
                "x &lt; y", "font-family: monospace");
    }

    @Test
    void showsHtmlAsText() {
        String html = html("<script>alert(1)</script> <b onclick=\"x\">hi</b>");
        assertThat(html).doesNotContain("<script", "<b ").contains("&lt;script&gt;");
    }

    @Test
    void allowsOnlySafeLinksAndMakesOwnOnesAbsolute() {
        assertThat(html("[x](javascript:alert(1))")).doesNotContain("href").contains("x");
        assertThat(html("[x](//evil.example/)")).doesNotContain("href");
        assertThat(html("see https://example.org/page")).contains("<a href=\"https://example.org/page\"");
        assertThat(html("[issues](/issues)")).contains("<a href=\"https://bugs.example.org/issues\"");
        assertThat(html("[log](attachment:server.log)")).contains("<a href=\"https://bugs.example.org/browse/BUG-1\"");
    }

    @Test
    void linksIssueKeysOfExistingProjects() {
        assertThat(html("Caused by BUG-12, not UTF-8.")).isEqualTo(
                "<p>Caused by <a href=\"https://bugs.example.org/browse/BUG-12\">BUG-12</a>, not UTF-8.</p>\n");
        assertThat(html("`BUG-12` and [BUG-12](https://example.org)")).doesNotContain("/browse/BUG-12");
    }

    @Test
    void showsNoImages() {
        String html = html("![shot](attachment:shot.png) ![pixel](https://evil.example/p.gif)");
        assertThat(html).doesNotContain("<img")
                .contains("<a href=\"https://bugs.example.org/browse/BUG-1\">[shot]</a>")
                .contains("<a href=\"https://evil.example/p.gif\">[pixel]</a>");
    }

    @Test
    void writesTaskListBoxesAsCharacters() {
        assertThat(html("- [ ] to do\n- [x] done")).contains("☐ to do", "☑ done").doesNotContain("<input");
    }
}
