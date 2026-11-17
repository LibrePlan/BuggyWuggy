// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { searchSections, searchTerms, snippet, type HelpSection } from './helpSearch';

const sections: HelpSection[] = [
  { id: null, title: 'User guide', text: 'BuggyWuggy keeps track of issues.' },
  { id: 'boards', title: 'Boards', text: 'Drag an issue to another column. A board shows issues per status.' },
  { id: 'filters', title: 'Filters', text: 'Filter the issue list; boards are not filtered.' },
  { id: 'mail', title: 'Mail', text: 'Notifications by e-mail.' },
];

describe('help search', () => {
  it('needs two characters and splits on spaces', () => {
    expect(searchTerms(' a ')).toEqual([]);
    expect(searchTerms('Board  ISSUE board')).toEqual(['board', 'issue']);
  });

  it('finds sections with every term, headings first', () => {
    const results = searchSections(sections, 'board');
    expect(results.map((r) => r.section.id)).toEqual(['boards', 'filters']);
    expect(results[0]!.hits).toBe(2);
    expect(searchSections(sections, 'board mail')).toEqual([]);
    expect(searchSections(sections, 'issue').map((r) => r.section.id)).toEqual(['boards', null, 'filters']);
  });

  it('cuts a snippet around the first hit', () => {
    const text = 'word '.repeat(40) + 'needle ' + 'word '.repeat(40);
    const s = snippet(text, ['needle'], 20);
    expect(s).toMatch(/^… (word )+needle( word)+ …$/);
    expect(snippet('short text', ['text'])).toBe('short text');
  });
});
