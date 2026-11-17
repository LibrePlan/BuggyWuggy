// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { HistoryItem } from '../api/types';

/** A value in the history: its text, and where it leads (an issue, or a page elsewhere). */
export interface HistoryValue {
  text: string;
  /** A date (YYYY-MM-DD), to be formatted for the user. */
  date?: string;
  /** An issue key: the value links to the issue. */
  issueKey?: string;
  /** An http(s) URL outside BuggyWuggy. */
  url?: string;
}

/** One line of a history entry. */
export type HistoryRow =
  | { kind: 'change'; field: string; from: HistoryValue | null; to: HistoryValue | null }
  | { kind: 'set'; field: string; added: string[]; removed: string[] };

/**
 * Fields with several values. Components and versions are recorded one item per added or
 * removed value, labels as the whole list before and after (space-separated): both are shown
 * the same way, as the values added and removed.
 */
const PER_VALUE = new Set(['component', 'version', 'fixversion']);
const WHOLE_LIST = new Set(['labels']);

const ISSUE_KEY = /^[A-Z][A-Z0-9_]*-\d+$/;
const HTTP_URL = /^https?:\/\//i;

function value(item: HistoryItem, side: 'from' | 'to'): HistoryValue | null {
  const raw = side === 'from' ? item.from : item.to;
  const text = (side === 'from' ? item.fromText : item.toText) ?? raw;
  if (text === null || text === '') return null;
  switch (item.field) {
    case 'duedate':
      return raw && /^\d{4}-\d{2}-\d{2}$/.test(raw) ? { text, date: raw } : { text };
    case 'link': // value: the key of the other issue; text: e.g. "blocks HIS-3"
      return raw && ISSUE_KEY.test(raw) ? { text, issueKey: raw } : { text };
    case 'parent': // value: the id; text: the key
      return ISSUE_KEY.test(text) ? { text, issueKey: text } : { text };
    case 'import':
    case 'remotelink': // value: the URL; text: e.g. "Forgejo owner/repo#12" or a pull request title
      return raw && HTTP_URL.test(raw) ? { text, url: raw } : { text };
    default:
      return { text };
  }
}

function words(text: string | null): string[] {
  return text ? text.split(/\s+/).filter(Boolean) : [];
}

/** The lines of one history entry, in the order of its items. */
export function historyRows(items: HistoryItem[]): HistoryRow[] {
  const rows: HistoryRow[] = [];
  const sets = new Map<string, Extract<HistoryRow, { kind: 'set' }>>();
  const setRow = (field: string) => {
    let row = sets.get(field);
    if (!row) {
      row = { kind: 'set', field, added: [], removed: [] };
      sets.set(field, row);
      rows.push(row);
    }
    return row;
  };
  for (const item of items) {
    if (PER_VALUE.has(item.field)) {
      const row = setRow(item.field);
      const removed = item.fromText ?? item.from;
      const added = item.toText ?? item.to;
      if (removed) row.removed.push(removed);
      if (added) row.added.push(added);
    } else if (WHOLE_LIST.has(item.field)) {
      const row = setRow(item.field);
      const before = words(item.fromText ?? item.from);
      const after = words(item.toText ?? item.to);
      row.added.push(...after.filter((w) => !before.includes(w)));
      row.removed.push(...before.filter((w) => !after.includes(w)));
    } else {
      rows.push({ kind: 'change', field: item.field, from: value(item, 'from'), to: value(item, 'to') });
    }
  }
  return rows.filter((r) => r.kind === 'change' || r.added.length > 0 || r.removed.length > 0);
}
