// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

/** A part of a help page: the text under one heading (or before the first heading). */
export interface HelpSection {
  /** The anchor of the heading; null for the text before the first heading. */
  id: string | null;
  title: string;
  text: string;
}

export interface HelpResult {
  index: number;
  section: HelpSection;
  /** Occurrences of the terms in the title and the text. */
  hits: number;
  snippet: string;
}

/** The search terms: lower case, without duplicates; nothing for a query shorter than 2 characters. */
export function searchTerms(query: string): string[] {
  const trimmed = query.trim().toLowerCase();
  if (trimmed.length < 2) return [];
  return [...new Set(trimmed.split(/\s+/))];
}

function count(haystack: string, term: string): number {
  let n = 0;
  for (let i = haystack.indexOf(term); i >= 0; i = haystack.indexOf(term, i + term.length)) n++;
  return n;
}

/**
 * The sections that contain every term, best first: hits in the heading weigh more than
 * hits in the text; equal ones stay in page order.
 */
export function searchSections(sections: HelpSection[], query: string): HelpResult[] {
  const terms = searchTerms(query);
  if (!terms.length) return [];
  const results: (HelpResult & { score: number })[] = [];
  sections.forEach((section, index) => {
    const title = section.title.toLowerCase();
    const text = section.text.toLowerCase();
    let score = 0;
    let hits = 0;
    for (const term of terms) {
      const inTitle = count(title, term);
      const inText = count(text, term);
      if (inTitle + inText === 0) return;
      hits += inTitle + inText;
      score += inTitle * 10 + inText;
    }
    results.push({ index, section, hits, score, snippet: snippet(section.text, terms) });
  });
  return results
    .sort((a, b) => b.score - a.score || a.index - b.index)
    .map(({ score: _score, ...result }) => result);
}

/** A piece of {@code text} around the first occurrence of a term, cut at word boundaries. */
export function snippet(text: string, terms: string[], radius = 70): string {
  const flat = text.replace(/\s+/g, ' ').trim();
  const lower = flat.toLowerCase();
  const positions = terms.map((t) => lower.indexOf(t)).filter((p) => p >= 0);
  const at = positions.length ? Math.min(...positions) : 0;
  let start = Math.max(0, at - radius);
  let end = Math.min(flat.length, at + radius);
  if (start > 0) start = flat.indexOf(' ', start) + 1 || start;
  if (end < flat.length) end = flat.lastIndexOf(' ', end) > at ? flat.lastIndexOf(' ', end) : end;
  return (start > 0 ? '… ' : '') + flat.slice(start, end) + (end < flat.length ? ' …' : '');
}
