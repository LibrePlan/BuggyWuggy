// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { ActionIcon, CloseButton, Group, Highlight, Kbd, ScrollArea, Stack, Text, TextInput, UnstyledButton } from '@mantine/core';
import { IconChevronDown, IconChevronUp, IconSearch } from '@tabler/icons-react';
import { useCallback, useEffect, useMemo, useRef, useState, type RefObject } from 'react';
import { useTranslation } from 'react-i18next';
import { searchSections, searchTerms, type HelpResult, type HelpSection } from '../lib/helpSearch';

interface DomSection extends HelpSection {
  nodes: Text[];
}

interface Match {
  range: Range;
  section: number;
}

const MATCH = 'bw-help-match';
/** Elements whose text is separated from the next by a space in the section text. */
const BLOCK = 'p, li, td, th, pre, blockquote, dt, dd, h1, h2, h3, h4, h5, h6';
const CURRENT = 'bw-help-current';

/** The CSS Custom Highlight API: marks text without changing the page; not in every browser. */
function highlights(): HighlightRegistry | undefined {
  return typeof CSS !== 'undefined' && 'highlights' in CSS ? CSS.highlights : undefined;
}

/** The sections of the rendered help page: the text nodes under each heading with an id. */
function readSections(root: HTMLElement, pageTitle: string): DomSection[] {
  let section: DomSection = { id: null, title: pageTitle, text: '', nodes: [] };
  const sections: DomSection[] = [section];
  let heading: Element | null = null;
  let block: Element | null | undefined;
  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
  for (let node = walker.nextNode() as Text | null; node; node = walker.nextNode() as Text | null) {
    const h = node.parentElement?.closest('h1, h2, h3, h4, h5, h6');
    if (h && h.id) {
      if (h !== heading) {
        heading = h;
        section = { id: h.id, title: '', text: '', nodes: [] };
        sections.push(section);
      }
      section.title += node.data;
    } else {
      // Text of inline elements (links, code, emphasis) joins without a space; blocks do not.
      const parent = node.parentElement?.closest(BLOCK) ?? null;
      section.text += (parent !== block ? ' ' : '') + node.data;
      block = parent;
    }
    section.nodes.push(node);
  }
  return sections.filter((s) => s.nodes.length);
}

/** Every occurrence of the terms in the given sections, in page order. */
function findMatches(sections: DomSection[], wanted: Set<number>, terms: string[]): Match[] {
  const matches: Match[] = [];
  sections.forEach((section, index) => {
    if (!wanted.has(index)) return;
    for (const node of section.nodes) {
      const lower = node.data.toLowerCase();
      const found: [number, number][] = [];
      for (const term of terms) {
        for (let i = lower.indexOf(term); i >= 0; i = lower.indexOf(term, i + term.length)) found.push([i, i + term.length]);
      }
      found.sort((a, b) => a[0] - b[0]);
      let last = -1;
      for (const [start, end] of found) {
        if (start < last) continue;
        const range = document.createRange();
        range.setStart(node, start);
        range.setEnd(node, end);
        matches.push({ range, section: index });
        last = end;
      }
    }
  });
  return matches;
}

/**
 * Searches the help page that is shown in {@code content}: the sections that contain all
 * words, with every occurrence marked in the text. Enter and Shift+Enter go to the next and
 * previous occurrence, Escape clears, and "/" anywhere on the page puts the cursor here.
 */
export function HelpSearch({
  content,
  pageTitle,
  html,
  query,
  onQueryChange,
  maxResultsHeight,
}: {
  content: RefObject<HTMLElement | null>;
  pageTitle: string;
  /** The page's HTML: when it changes, the sections are read again. */
  html: string;
  query: string;
  onQueryChange: (query: string) => void;
  maxResultsHeight: string;
}) {
  const { t } = useTranslation();
  const input = useRef<HTMLInputElement>(null);
  const [sections, setSections] = useState<DomSection[]>([]);
  const [current, setCurrent] = useState(-1);
  const [showResults, setShowResults] = useState(true);

  useEffect(() => {
    if (content.current) setSections(readSections(content.current, pageTitle));
  }, [content, html, pageTitle]);

  const terms = useMemo(() => searchTerms(query), [query]);
  const results = useMemo(() => searchSections(sections, query), [sections, query]);
  const matches = useMemo(
    () => findMatches(sections, new Set(results.map((r) => r.index)), terms),
    [sections, results, terms],
  );

  // Mark all occurrences; the current one stands out.
  useEffect(() => {
    const registry = highlights();
    if (!registry) return;
    registry.set(MATCH, new globalThis.Highlight(...matches.map((m) => m.range)));
    return () => {
      registry.delete(MATCH);
    };
  }, [matches]);
  useEffect(() => {
    const registry = highlights();
    const match = matches[current];
    if (!registry || !match) return;
    registry.set(CURRENT, new globalThis.Highlight(match.range));
    return () => {
      registry.delete(CURRENT);
    };
  }, [matches, current]);
  useEffect(() => setCurrent(-1), [matches]);

  const goTo = useCallback(
    (index: number) => {
      const match = matches[index];
      if (!match) return;
      setCurrent(index);
      const rect = match.range.getBoundingClientRect();
      window.scrollTo({ top: window.scrollY + rect.top - window.innerHeight / 3, behavior: 'smooth' });
    },
    [matches],
  );
  const step = (by: number) => {
    if (!matches.length) return;
    goTo((current + by + matches.length) % matches.length);
  };
  const open = (result: HelpResult) => {
    goTo(matches.findIndex((m) => m.section === result.index));
    setShowResults(false);
  };

  // "/" outside a text field: search.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      if (e.key !== '/' || e.ctrlKey || e.metaKey || e.altKey) return;
      if (target?.closest('input, textarea, select, [contenteditable="true"]')) return;
      e.preventDefault();
      input.current?.focus();
      input.current?.select();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  const active = terms.length > 0;
  return (
    <Stack gap="xs">
      <TextInput
        ref={input}
        value={query}
        onChange={(e) => {
          onQueryChange(e.currentTarget.value);
          setShowResults(true);
        }}
        onFocus={() => setShowResults(true)}
        onKeyDown={(e) => {
          if (e.key === 'Enter') {
            e.preventDefault();
            step(e.shiftKey ? -1 : 1);
          } else if (e.key === 'Escape') {
            onQueryChange('');
          }
        }}
        placeholder={t('help.search.placeholder')}
        aria-label={t('help.search.placeholder')}
        leftSection={<IconSearch size={16} />}
        rightSectionWidth={active ? 128 : 36}
        rightSectionPointerEvents="all"
        rightSection={
          active ? (
            <Group gap={2} wrap="nowrap" pr={4}>
              <Text size="xs" c="dimmed" miw={44} ta="right" aria-live="polite">
                {matches.length ? t('help.search.counter', { current: current + 1 || '–', total: matches.length }) : '0'}
              </Text>
              <ActionIcon variant="subtle" color="gray" size="sm" disabled={!matches.length} onClick={() => step(-1)} aria-label={t('help.search.previous')}>
                <IconChevronUp size={14} />
              </ActionIcon>
              <ActionIcon variant="subtle" color="gray" size="sm" disabled={!matches.length} onClick={() => step(1)} aria-label={t('help.search.next')}>
                <IconChevronDown size={14} />
              </ActionIcon>
              <CloseButton size="sm" onClick={() => onQueryChange('')} aria-label={t('help.search.clear')} />
            </Group>
          ) : (
            <Kbd size="xs">/</Kbd>
          )
        }
      />
      {active && showResults && (
        <ScrollArea.Autosize mah={maxResultsHeight} type="hover">
          {results.length === 0 ? (
            <Text size="sm" c="dimmed" px="xs">
              {t('help.search.nothing', { query: query.trim() })}
            </Text>
          ) : (
            <Stack gap={2}>
              <Text size="xs" c="dimmed" px="xs">
                {t('help.search.sections', { count: results.length })} · {t('help.search.keys')}
              </Text>
              {results.map((r) => (
                <UnstyledButton key={r.index} onClick={() => open(r)} className="bw-help-result" px="xs" py={6}>
                  <Highlight highlight={terms} size="sm" fw={600} lineClamp={1}>
                    {r.section.title}
                  </Highlight>
                  <Highlight highlight={terms} size="xs" c="dimmed" lineClamp={2}>
                    {r.snippet}
                  </Highlight>
                </UnstyledButton>
              ))}
            </Stack>
          )}
        </ScrollArea.Autosize>
      )}
    </Stack>
  );
}
