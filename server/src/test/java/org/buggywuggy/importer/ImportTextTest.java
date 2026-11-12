/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ImportTextTest {

    private static final String BASE = "https://code.example.org";

    private final ImportText text = new ImportText(BASE, "acme/widgets",
            Pattern.compile("(?:" + Pattern.quote(BASE) + ")?/attachments/([0-9a-fA-F-]{36})\\b"),
            new ImportText.Resolver() {
        @Override
        public Optional<String> issueKey(String repository, long number) {
            Map<String, String> keys = repository == null ? Map.of("1", "WID-1", "3", "WID-2")
                    : repository.equals("acme/gears") ? Map.of("5", "GEAR-9") : Map.of();
            return Optional.ofNullable(keys.get(String.valueOf(number)));
        }

        @Override
        public Optional<Long> attachmentId(String uuid) {
            return uuid.startsWith("aaaaaaaa") ? Optional.of(42L) : Optional.empty();
        }
    });

    @Test
    void referencesBecomeKeysOrLinksToTheOriginal() {
        assertThat(text.rewrite("See #1, #2 and acme/gears#5 or other/repo#7."))
                .isEqualTo("See WID-1, [#2](" + BASE + "/acme/widgets/issues/2) and GEAR-9 or "
                        + "[other/repo#7](" + BASE + "/other/repo/issues/7).");
        assertThat(text.rewrite("acme/widgets#3")).isEqualTo("WID-2");
        // Bare links to issues of the source too; links with a text keep it.
        assertThat(text.rewrite("Like " + BASE + "/acme/widgets/issues/1 and [this](" + BASE
                + "/acme/widgets/issues/1)."))
                .isEqualTo("Like WID-1 and [this](" + BASE + "/acme/widgets/issues/1).");
    }

    @Test
    void leavesCodeLinksAnchorsAndEntitiesAlone() {
        String untouched = """
                `#1` and

                ```
                #1
                ```
                [issue #1](https://elsewhere.example/x#1) https://elsewhere.example/page#1 &#35; # Heading""";
        assertThat(text.rewrite(untouched)).isEqualTo(untouched);
    }

    @Test
    void pointsAttachmentsToTheCopyAndMakesRelativeLinksAbsolute() {
        String copied = "aaaaaaaa-1111-2222-3333-444444444444";
        String missing = "bbbbbbbb-1111-2222-3333-444444444444";
        assertThat(text.rewrite("![a](/attachments/" + copied + ") ![b](" + BASE + "/attachments/" + missing
                + ") [commit](/acme/widgets/commit/abc)"))
                .isEqualTo("![a](/api/v1/attachments/42/content) ![b](" + BASE + "/attachments/" + missing
                        + ") [commit](" + BASE + "/acme/widgets/commit/abc)");
    }
}
