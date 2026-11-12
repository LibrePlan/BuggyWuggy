/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.buggywuggy.mail.TemplateEngine.Keyword;
import org.buggywuggy.mail.TemplateEngine.Problem;
import org.junit.jupiter.api.Test;

class TemplateEngineTest {

    private static final Map<String, Keyword> KEYWORDS = Map.of(
            "issue.key", new Keyword("issue.key", false),
            "issue.summary", new Keyword("issue.summary", false),
            "changes", new Keyword("changes", true));

    @Test
    void acceptsKnownKeywordsWithOrWithoutSpaces() {
        assertThat(TemplateEngine.validate("{{issue.key}} {{ issue.summary }}\n{{changes}}", KEYWORDS, false))
                .isEmpty();
    }

    @Test
    void reportsUnknownKeywordsWithTheirLine() {
        assertThat(TemplateEngine.validate("Hello\n\n{{issue.kee}}", KEYWORDS, false))
                .containsExactly(new Problem(3, "mail-template.unknown-keyword", "issue.kee"));
    }

    @Test
    void reportsUnclosedKeywords() {
        assertThat(TemplateEngine.validate("{{issue.key} and {{issue.summary}}", KEYWORDS, false))
                .containsExactly(new Problem(1, "mail-template.unclosed", null));
        assertThat(TemplateEngine.validate("text {{", KEYWORDS, false))
                .containsExactly(new Problem(1, "mail-template.unclosed", null));
    }

    @Test
    void blockKeywordsAreNotAllowedInTheSubject() {
        assertThat(TemplateEngine.validate("[{{issue.key}}] {{changes}}", KEYWORDS, true))
                .containsExactly(new Problem(1, "mail-template.block-in-subject", "changes"));
    }

    @Test
    void escapesValuesInHtmlAndDoesNotRenderKeywordsInValues() {
        String html = TemplateEngine.renderHtml("<b>{{issue.summary}}</b>{{changes}}", KEYWORDS,
                name -> name.equals("issue.summary") ? "<script>x</script> {{issue.key}}" : null,
                name -> "<table></table>");
        assertThat(html).isEqualTo("<b>&lt;script&gt;x&lt;/script&gt; {{issue.key}}</b><table></table>");
    }

    @Test
    void subjectsAreOneLine() {
        assertThat(TemplateEngine.renderSubject("[{{issue.key}}] {{issue.summary}}",
                name -> name.equals("issue.key") ? "BUG-1" : "Two\r\nlines")).isEqualTo("[BUG-1] Two lines");
    }
}
