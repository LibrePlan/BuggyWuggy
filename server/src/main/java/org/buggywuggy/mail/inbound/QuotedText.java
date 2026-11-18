/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.mail.inbound;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Splits an answer into what was written and what was quoted from earlier mails: from the line
 * that introduces the quote ("On … wrote:", "Op … schreef …:", an Outlook header block, a
 * separator line) to the end, or a block of "> " lines at the end. When nothing is left before
 * the quote, the whole text counts as written.
 */
final class QuotedText {

    record Parts(String written, String quoted) {
    }

    /** Lines that introduce a quote, in the languages mail programs use. */
    private static final List<Pattern> INTRODUCTIONS = List.of(
            // English, Dutch, German, French, Spanish, Italian, Portuguese: "On … wrote:"
            Pattern.compile("(?i)^\\s*(on|op|am|le|el|il|em)\\s.+(wrote|schreef|schrieb|a écrit|escribió|ha scritto"
                    + "|escreveu)\\s*.*:\\s*$"),
            Pattern.compile("(?i)^\\s*-{2,}\\s*(original message|oorspronkelijk bericht|origineel bericht"
                    + "|ursprüngliche nachricht|message d'origine|forwarded message|doorgestuurd bericht)\\s*-{2,}\\s*$"),
            Pattern.compile("^\\s*_{20,}\\s*$"));
    /** Outlook: "From:" followed by "Sent:" or "Date:" within a few lines. */
    private static final Pattern OUTLOOK_FROM = Pattern.compile("(?i)^\\s*\\*?(from|van|von|de)\\s*:\\*?\\s+.+");
    private static final Pattern OUTLOOK_NEXT = Pattern.compile("(?i)^\\s*\\*?(sent|date|verzonden|datum|gesendet"
            + "|envoyé|to|aan|an|à)\\s*:\\*?.*");
    /** Gmail sometimes breaks "On … wrote:" over two lines. */
    private static final Pattern WROTE_END = Pattern.compile("(?i).*(wrote|schreef|schrieb|a écrit):\\s*$");

    private QuotedText() {
    }

    static Parts split(String text) {
        if (text == null || text.isBlank()) {
            return new Parts(text == null ? "" : text.strip(), null);
        }
        String[] lines = text.replace("\r\n", "\n").split("\n", -1);
        int start = -1;
        for (int i = 0; i < lines.length && start < 0; i++) {
            String line = lines[i];
            for (Pattern p : INTRODUCTIONS) {
                if (p.matcher(line).matches()) {
                    start = i;
                }
            }
            if (start < 0 && i + 1 < lines.length && line.matches("(?i)^\\s*(on|op|am|le)\\s.+")
                    && WROTE_END.matcher(lines[i + 1]).matches()) {
                start = i;
            }
            if (start < 0 && OUTLOOK_FROM.matcher(line).matches()) {
                for (int j = i + 1; j < Math.min(lines.length, i + 4); j++) {
                    if (OUTLOOK_NEXT.matcher(lines[j]).matches()) {
                        start = i;
                        break;
                    }
                }
            }
        }
        if (start < 0) {
            // A block of quoted lines at the end (with blank lines in between).
            int end = lines.length - 1;
            while (end >= 0 && lines[end].isBlank()) {
                end--;
            }
            int i = end;
            while (i >= 0 && (lines[i].startsWith(">") || lines[i].isBlank())) {
                i--;
            }
            if (i >= 0 && i < end) {
                start = i + 1;
                while (lines[start].isBlank()) {
                    start++;
                }
            }
        }
        if (start < 0) {
            return new Parts(text.strip(), null);
        }
        String written = String.join("\n", java.util.Arrays.copyOfRange(lines, 0, start)).strip();
        String quoted = String.join("\n", java.util.Arrays.copyOfRange(lines, start, lines.length)).strip();
        if (written.isEmpty()) {
            return new Parts(text.strip(), null);
        }
        return new Parts(written, quoted.isEmpty() ? null : quoted);
    }
}
