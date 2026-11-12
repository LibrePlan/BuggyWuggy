/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HtmlMailTest {

    @Test
    void removesScriptsHandlersAndJavascriptLinksButKeepsStyling() {
        String clean = HtmlMail.clean("""
                <p style="color: red" onclick="steal()">Hi<script>alert(1)</script></p>
                <a href="javascript:alert(1)">bad</a> <a href="https://example.org/x">good</a>
                <form action="https://evil"><input name="password"></form>
                <table border="1" cellpadding="4"><tr><td bgcolor="#eee">cell</td></tr></table>""");
        assertThat(clean).contains("<p style=\"color: red\">Hi</p>")
                .doesNotContain("script", "onclick", "javascript", "<form", "<input")
                .contains("<a href=\"https://example.org/x\">good</a>")
                .contains("<table border=\"1\" cellpadding=\"4\">", "bgcolor=\"#eee\"");
    }

    @Test
    void derivesReadablePlainText() {
        String text = HtmlMail.text("""
                <h2><a href="https://bugs/BUG-1">BUG-1</a> Login fails</h2>
                <p>Ada   commented:</p><div>Line one<br>Line two</div>
                <ul><li>first</li><li>second</li></ul>
                <table><tr><td>Status</td><td>Open → Done</td></tr></table>""");
        assertThat(text).isEqualTo("""
                BUG-1 <https://bugs/BUG-1> Login fails

                Ada commented:

                Line one
                Line two
                - first
                - second

                Status  Open → Done
                """);
    }
}
