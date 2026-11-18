/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer.bugzilla;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.buggywuggy.common.PlainText;

/**
 * Bugzilla comments (plain text) as Markdown that looks the same (see {@link PlainText}).
 * "attachment 12" becomes a link to the attachment, which the import points to the copied file.
 * Comments that Bugzilla 5.2 marks as Markdown are kept as they are.
 */
final class BugzillaText {

    private static final Pattern ATTACHMENT = Pattern.compile("(?i)\\b(attachment) (\\d+)\\b");

    private BugzillaText() {
    }

    /** {@code web}: the address of Bugzilla, for links to attachments. */
    static String toMarkdown(String text, boolean markdown, String web) {
        if (markdown) {
            return text;
        }
        return PlainText.toMarkdown(text, prose -> ATTACHMENT.matcher(prose).replaceAll(m -> Matcher.quoteReplacement(
                "[" + m.group(1) + " " + m.group(2) + "](" + web + "/attachment.cgi?id=" + m.group(2) + ")")));
    }
}
