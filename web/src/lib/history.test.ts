// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import type { HistoryItem } from '../api/types';
import { historyRows } from './history';

const item = (field: string, from: string | null, fromText: string | null, to: string | null, toText: string | null) =>
  ({ field, from, fromText, to, toText }) as HistoryItem;

describe('historyRows', () => {
  it('shows components, versions and labels as values added and removed', () => {
    expect(
      historyRows([
        item('labels', null, 'login ui', null, 'login security urgent-fix'),
        item('component', null, null, '2', 'Frontend'),
        item('component', '1', 'Backend', null, null),
        item('fixversion', null, null, '2', '1.1'),
      ]),
    ).toEqual([
      { kind: 'set', field: 'labels', added: ['security', 'urgent-fix'], removed: ['ui'] },
      { kind: 'set', field: 'component', added: ['Frontend'], removed: ['Backend'] },
      { kind: 'set', field: 'fixversion', added: ['1.1'], removed: [] },
    ]);
  });

  it('leaves out a list that did not change', () => {
    expect(historyRows([item('labels', null, 'b a', null, 'a b')])).toEqual([]);
  });

  it('knows which values lead to an issue, a page elsewhere or a date', () => {
    const [link, parent, origin, pr, due, summary] = historyRows([
      item('link', null, null, 'HIS-3', 'blocks HIS-3'),
      item('parent', '1', 'HIS-1', null, null),
      item('import', null, null, 'https://codeberg.org/o/r/issues/12', 'Forgejo o/r#12'),
      item('remotelink', null, null, 'javascript:alert(1)', 'Evil'),
      item('duedate', '2026-12-01', '2026-12-01', null, null),
      item('summary', null, 'Old', null, 'New'),
    ]);
    expect(link).toMatchObject({ from: null, to: { text: 'blocks HIS-3', issueKey: 'HIS-3' } });
    expect(parent).toMatchObject({ from: { text: 'HIS-1', issueKey: 'HIS-1' }, to: null });
    expect(origin).toMatchObject({ to: { url: 'https://codeberg.org/o/r/issues/12' } });
    expect(pr).toEqual({ kind: 'change', field: 'remotelink', from: null, to: { text: 'Evil' } });
    expect(due).toMatchObject({ from: { date: '2026-12-01' }, to: null });
    expect(summary).toEqual({ kind: 'change', field: 'summary', from: { text: 'Old' }, to: { text: 'New' } });
  });
});
