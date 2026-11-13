/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.redmine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RedmineTextTest {

    private static final RedmineText.Context CONTEXT = new RedmineText.Context() {
        @Override
        public String attachment(String fileName) {
            return switch (fileName) {
                case "shot.png" -> "/attachments/download/7/shot.png";
                case "log file.txt" -> "/attachments/download/8/log%20file.txt";
                default -> null;
            };
        }

        @Override
        public String wiki(String project, String page) {
            return "https://redmine.example/projects/" + (project == null ? "web" : project) + "/wiki/"
                    + page.replace(' ', '_');
        }
    };

    private static String textile(String text) {
        return RedmineText.toMarkdown(text, true, CONTEXT);
    }

    private static String markdown(String text) {
        return RedmineText.toMarkdown(text, false, CONTEXT);
    }

    @Test
    void guessesTheFormat() {
        assertThat(RedmineText.looksLikeTextile(List.of("h2. Title\n\nSee \"the docs\":https://x.example", "plain")))
                .isTrue();
        assertThat(RedmineText.looksLikeTextile(List.of("## Title\n\nSee [the docs](https://x.example)", "`a`")))
                .isFalse();
        assertThat(RedmineText.looksLikeTextile(List.of("Just text, #12 and *maybe* bold."))).isFalse();
    }

    @Test
    void convertsTextileInline() {
        assertThat(textile("*bold* _italic_ __also__ -gone- +ins+ ??cited?? @a_b*c*@ %{color:red}red% **strong**"))
                .isEqualTo("**bold** *italic* *also* ~~gone~~ ins *cited* `a_b*c*` red **strong**");
        assertThat(textile("\"a page\":https://example.org/a_b_c, [\"x\":https://example.org/y] and "
                + "\"titled(The title)\":/projects/web."))
                .isEqualTo("[a page](https://example.org/a_b_c), [x](https://example.org/y) and "
                        + "[titled](/projects/web).");
        assertThat(textile("!shot.png! !>shot.png(A shot)! !other.png! attachment:\"log file.txt\" "
                + "attachment:missing.txt [[Start page]] [[ops:Run book|the book]] ##12 and #13"))
                .isEqualTo("![shot.png](/attachments/download/7/shot.png) "
                        + "![A shot](/attachments/download/7/shot.png) !other.png! "
                        + "[log file.txt](/attachments/download/8/log%20file.txt) missing.txt "
                        + "[Start page](https://redmine.example/projects/web/wiki/Start_page) "
                        + "[the book](https://redmine.example/projects/ops/wiki/Run_book) #12 and #13");
        // Not markup: arithmetic, snake_case, e-mail addresses, exclamations.
        assertThat(textile("2 * 3 * 4, a_name_here, 10 - 5 - 2, mail ann@example.org or bob@example.org! Wow!"))
                .isEqualTo("2 * 3 * 4, a_name_here, 10 - 5 - 2, mail ann@example.org or bob@example.org! Wow!");
    }

    @Test
    void convertsTextileBlocks() {
        assertThat(textile("""
                {{toc}}
                h1. Title
                h2(#id). Second
                bq. Wise words
                p>. A paragraph
                # one
                ## one.one
                #* bullet
                * star
                |_. Name |_. Value |
                |a|"link":https://example.org|
                |>. right|@x|y@|
                table{border:1px}.
                |no|header|"""))
                .isEqualTo("""
                        # Title
                        ## Second
                        > Wise words
                        A paragraph
                        1. one
                           1. one.one
                           - bullet
                        - star
                        | Name | Value |
                        | --- | --- |
                        | a | [link](https://example.org) |
                        | right | `x\\|y` |
                        | no | header |
                        | --- | --- |""");
    }

    @Test
    void leavesTextileCodeAlone() {
        assertThat(textile("Before\n<pre><code class=\"ruby\">\nx = *a* _b_\n</code></pre>\nAfter <pre>h1. no</pre>"))
                .isEqualTo("Before\n```ruby\nx = *a* _b_\n```\nAfter \n```\nh1. no\n```");
        assertThat(textile("<notextile>*as is*</notextile> and ==*raw*== and <code>*c*</code>"))
                .isEqualTo("*as is* and *raw* and `*c*`");
    }

    @Test
    void keepsMarkdownButResolvesRedmineLinks() {
        assertThat(markdown("""
                ## Title
                ![](shot.png) [the log](log file.txt) [site](https://example.org) attachment:shot.png
                `attachment:shot.png` [[Start page]] ##5
                ```
                ![](shot.png)
                ```"""))
                .isEqualTo("""
                        ## Title
                        ![](/attachments/download/7/shot.png) [the log](log file.txt) [site](https://example.org) \
                        [shot.png](/attachments/download/7/shot.png)
                        `attachment:shot.png` [Start page](https://redmine.example/projects/web/wiki/Start_page) #5
                        ```
                        ![](shot.png)
                        ```""");
    }
}
