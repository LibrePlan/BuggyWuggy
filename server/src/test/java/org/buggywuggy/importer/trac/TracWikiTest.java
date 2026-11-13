/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.trac;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TracWikiTest {

    private static final String WEB = "https://trac.example/proj";

    private static final TracWiki.Context CONTEXT = new TracWiki.Context() {
        @Override
        public String attachment(String ticket, String fileName) {
            if (ticket != null) {
                return WEB + "/raw-attachment/ticket/" + ticket + "/" + fileName;
            }
            return fileName.equals("shot.png") ? "/raw-attachment/ticket/7/shot.png" : null;
        }

        @Override
        public String web() {
            return WEB;
        }
    };

    private static String md(String wiki) {
        return TracWiki.toMarkdown(wiki, CONTEXT);
    }

    @Test
    void convertsTextEffectsAndCode() {
        assertThat(md("'''bold''' ''italic'' '''''both''''' **strong** //em// __under__ ~~gone~~ {{{a_''b''}}} `c`"))
                .isEqualTo("**bold** *italic* ***both*** **strong** *em* under ~~gone~~ `a_''b''` `c`");
        assertThat(md("See http://example.org/a//b//c, x_y_z and 2 * 3 * 4."))
                .isEqualTo("See http://example.org/a//b//c, x_y_z and 2 * 3 * 4.");
    }

    @Test
    void convertsLinks() {
        assertThat(md("[http://example.org/x the site] [https://example.org/y] [wiki:StartPage start] "
                + "[[wiki:Run book|the book]] CamelCase [CamelCase] ticket:12, [ticket:13 the bug] #14 "
                + "[[ticket:15]] attachment:shot.png [attachment:other.txt:ticket:3 log] milestone:1.0 "
                + "!WikiWord ![not a link]"))
                .isEqualTo("[the site](http://example.org/x) <https://example.org/y> "
                        + "[start](https://trac.example/proj/wiki/StartPage) "
                        + "[the book](https://trac.example/proj/wiki/Run%20book) CamelCase "
                        + "[CamelCase](https://trac.example/proj/wiki/CamelCase) #12, the bug (#13) #14 #15 "
                        + "[shot.png](/raw-attachment/ticket/7/shot.png) "
                        + "[log](https://trac.example/proj/raw-attachment/ticket/3/other.txt) "
                        + "[1.0](https://trac.example/proj/milestone/1.0) WikiWord [not a link]");
        assertThat(md("[[Image(shot.png)]] [[Image(missing.png, 50%)]] line[[BR]]break [[PageOutline]]"))
                .isEqualTo("![shot.png](/raw-attachment/ticket/7/shot.png) [[Image(missing.png, 50%)]] line\nbreak ");
    }

    @Test
    void convertsBlocks() {
        assertThat(md("""
                = Title =
                == Second ==#anchor
                 * one
                   * one.one
                 1. first
                ----
                ||= Name =||= Value =||
                ||a||[http://example.org link]||
                  Quoted '''text'''
                {{{
                #!python
                x = '''a'''
                }}}
                {{{
                #!comment
                hidden
                }}}
                end"""))
                .isEqualTo("""
                        # Title
                        ## Second
                        - one
                           - one.one
                        1. first
                        ---
                        | Name | Value |
                        | --- | --- |
                        | a | [link](http://example.org) |
                        > Quoted **text**
                        ```python
                        x = '''a'''
                        ```

                        end""");
    }
}
