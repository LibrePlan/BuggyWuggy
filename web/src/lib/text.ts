// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

/**
 * Replaces the selection {@code start}–{@code end} of {@code text} with {@code insertion};
 * returns the new text and the cursor position right after the insertion.
 */
export function insertAt(text: string, start: number, end: number, insertion: string) {
  const from = Math.max(0, Math.min(start, text.length));
  const to = Math.max(from, Math.min(end, text.length));
  return { text: text.slice(0, from) + insertion + text.slice(to), cursor: from + insertion.length };
}
