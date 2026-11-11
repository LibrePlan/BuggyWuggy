// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

/** Must match Project.KEY_PATTERN on the server. */
export const PROJECT_KEY_PATTERN = /^[A-Z][A-Z0-9_]{1,9}$/;

/**
 * Suggests a project key from a project name, the way Jira does: the initials of a
 * multi-word name ("Customer Portal" → "CP"), or the start of a single word
 * ("Website" → "WEB"). Returns '' when no valid key can be made.
 */
export function suggestProjectKey(name: string): string {
  const words = name
    .normalize('NFKD')
    .replace(/[̀-ͯ]/g, '')
    .toUpperCase()
    .split(/[^A-Z0-9]+/)
    .filter((w) => w.length > 0);
  const firstLetter = words.findIndex((w) => /^[A-Z]/.test(w));
  if (firstLetter < 0) return '';
  const rest = words.slice(firstLetter);
  let key = rest.length === 1 ? rest[0]!.slice(0, 3) : rest.map((w) => w[0]).join('').slice(0, 10);
  if (key.length < 2) key = rest[0]!.slice(0, 3);
  return PROJECT_KEY_PATTERN.test(key) ? key : '';
}
