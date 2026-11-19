// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

/** Formats an ISO timestamp as a localized date and time, e.g. "12 Mar 2026, 14:05". */
export function formatDateTime(iso: string | null | undefined, locale: string, timeZone?: string): string {
  if (!iso) return '';
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short', timeZone }).format(new Date(iso));
}

/**
 * Formats an ISO date (yyyy-mm-dd) without shifting it through a time zone. A full timestamp
 * gives its local date; something that is not a date is shown as it is, never an error.
 */
export function formatDate(isoDate: string | null | undefined, locale: string): string {
  if (!isoDate) return '';
  const plain = /^(\d{4})-(\d{2})-(\d{2})$/.exec(isoDate);
  const date = plain
    ? new Date(Date.UTC(Number(plain[1]), Number(plain[2]) - 1, Number(plain[3])))
    : new Date(isoDate);
  if (Number.isNaN(date.getTime())) return isoDate;
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeZone: plain ? 'UTC' : undefined }).format(date);
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

/** A file size for people: "512 B", "1.5 KB", "12 MB" (binary units, as file managers show). */
export function formatBytes(bytes: number, locale: string): string {
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit++;
  }
  const digits = unit === 0 || value >= 10 ? 0 : 1;
  return `${new Intl.NumberFormat(locale, { maximumFractionDigits: digits }).format(value)} ${units[unit]}`;
}
