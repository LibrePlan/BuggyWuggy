/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.jira;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JiraMarkupTest {

    private static final JiraMarkup.Context CONTEXT = new JiraMarkup.Context() {
        @Override
        public String attachment(String fileName) {
            return fileName.equals("log file.txt") ? "/secure/attachment/7/log%20file.txt" : null;
        }

        @Override
        public String mention(String user) {
            return user.equals("5b10ac") ? "Ann Example" : user;
        }
    };

    private static String md(String wiki) {
        return JiraMarkup.toMarkdown(wiki, CONTEXT);
    }

    @Test
    void convertsTextEffectsLinksAndMentions() {
        assertThat(md("*bold* _italic_ -gone- ??cited?? {{a_b*c*}} [~jdoe] [~accountid:5b10ac]"))
                .isEqualTo("**bold** *italic* ~~gone~~ *cited* `a_b*c*` @jdoe @Ann Example");
        assertThat(md("[a page|https://example.org/a_b_c] [https://example.org/x_y_] [mailto:a@example.org]"))
                .isEqualTo("[a page](https://example.org/a_b_c) <https://example.org/x_y_> <mailto:a@example.org>");
        assertThat(md("[ABC-1] [the bug|ABC-2] [^log file.txt] [^missing.txt] !log file.txt! !other.png!"))
                .isEqualTo("ABC-1 the bug (ABC-2) [log file.txt](/secure/attachment/7/log%20file.txt) missing.txt "
                        + "![log file.txt](/secure/attachment/7/log%20file.txt) !other.png!");
        // Not markup: arithmetic, snake_case, file names, exclamations.
        assertThat(md("2 * 3 * 4, a_name_here, 10 - 5 - 2, Wow! Really!"))
                .isEqualTo("2 * 3 * 4, a_name_here, 10 - 5 - 2, Wow! Really!");
    }

    @Test
    void convertsBlocks() {
        assertThat(md("""
                h1. Title
                bq. Wise words
                # one
                ## one.one
                #* bullet
                - dash
                ----
                ||Name||Value||
                |a|[link|https://example.org]|
                {quote}
                quoted *text*
                {quote}
                {color:red}red{color} {panel:title=x}inside{panel}"""))
                .isEqualTo("""
                        # Title
                        > Wise words
                        1. one
                           1. one.one
                           - bullet
                        - dash
                        ---
                        | Name | Value |
                        | --- | --- |
                        | a | [link](https://example.org) |
                        > quoted **text**
                        red inside""");
    }

    @Test
    void leavesCodeAlone() {
        assertThat(md("Before\n{code:language=python|title=x.py}\nx = *a* _b_\n{code}\nAfter {noformat}h1. no{noformat}"))
                .isEqualTo("Before\n```python\nx = *a* _b_\n```\nAfter \n```\nh1. no\n```");
        assertThat(md("{code}\nuses ``` inside\n{code}")).isEqualTo("~~~\nuses ``` inside\n~~~");
    }
}
