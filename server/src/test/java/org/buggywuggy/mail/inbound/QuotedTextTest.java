/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.mail.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QuotedTextTest {

    @Test
    void gmailThunderbirdAndAppleMail() {
        var gmail = QuotedText.split("""
                Thanks, that works.

                On Wed, 18 Nov 2026 at 11:21, Support <support@example.org> wrote:
                > Use Export in the menu.""");
        assertThat(gmail.written()).isEqualTo("Thanks, that works.");
        assertThat(gmail.quoted()).startsWith("On Wed").endsWith("> Use Export in the menu.");

        var wrapped = QuotedText.split("""
                Fine.

                On Wed, 18 Nov 2026 at 11:21, Support Desk of a Long Company Name
                <support@example.org> wrote:
                > Hello""");
        assertThat(wrapped.written()).isEqualTo("Fine.");

        var dutch = QuotedText.split("Prima.\n\nOp 18 nov 2026 om 11:21 schreef Support <support@example.org>:\n> Hallo");
        assertThat(dutch.written()).isEqualTo("Prima.");
    }

    @Test
    void outlookAndSeparators() {
        var outlook = QuotedText.split("""
                Still broken.

                From: Support <support@example.org>
                Sent: Wednesday, 18 November 2026 11:21
                To: Carol
                Subject: Re: Printer""");
        assertThat(outlook.written()).isEqualTo("Still broken.");
        assertThat(outlook.quoted()).startsWith("From: Support");

        var original = QuotedText.split("Yes.\n\n-----Original Message-----\nFrom: x");
        assertThat(original.written()).isEqualTo("Yes.");
    }

    @Test
    void quotesAtTheEndAndInBetween() {
        var bottom = QuotedText.split("Agreed.\n\n> earlier\n> text\n");
        assertThat(bottom.written()).isEqualTo("Agreed.");
        assertThat(bottom.quoted()).isEqualTo("> earlier\n> text");

        // Answers between quotes: everything is written text.
        String inline = "> question 1\nanswer 1\n> question 2\nanswer 2";
        assertThat(QuotedText.split(inline).written()).isEqualTo(inline);
        assertThat(QuotedText.split(inline).quoted()).isNull();

        // Only a quote: nothing would be left, so it all counts as written.
        assertThat(QuotedText.split("> only quoted").written()).isEqualTo("> only quoted");
        // "From:" in a sentence is no Outlook header.
        assertThat(QuotedText.split("From: my point of view it works.").quoted()).isNull();
    }
}
