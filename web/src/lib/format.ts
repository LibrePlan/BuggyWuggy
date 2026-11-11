// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

/** Formats an ISO timestamp as a localized date and time, e.g. "12 Mar 2026, 14:05". */
export function formatDateTime(iso: string | null | undefined, locale: string, timeZone?: string): string {
  if (!iso) return '';
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short', timeZone }).format(new Date(iso));
}

/** Formats an ISO date (yyyy-mm-dd) without shifting it through a time zone. */
export function formatDate(isoDate: string | null | undefined, locale: string): string {
  if (!isoDate) return '';
  const [y, m, d] = isoDate.split('-').map(Number);
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeZone: 'UTC' }).format(
    new Date(Date.UTC(y!, m! - 1, d!)),
  );
}

/** Shallow difference of two values for PATCH requests: fields of {@code next} that differ. */
export function changedFields<T extends object>(current: T, next: Partial<T>): Partial<T> {
  const result: Partial<T> = {};
  for (const key of Object.keys(next) as (keyof T)[]) {
    if (!sameValue(current[key], next[key])) result[key] = next[key];
  }
  return result;
}

function sameValue(a: unknown, b: unknown): boolean {
  if (Array.isArray(a) && Array.isArray(b)) {
    return a.length === b.length && [...a].sort().join('\u0000') === [...b].sort().join('\u0000');
  }
  return (a ?? null) === (b ?? null);
}
