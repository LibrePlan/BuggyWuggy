// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

export type Direction = 'OUTWARD' | 'INWARD';

interface LinkType {
  id: number;
  name: string;
  outward: string;
  inward: string;
}

export interface LinkOption {
  value: string;
  label: string;
  linkTypeId: number;
  direction: Direction;
}

/**
 * The choices for "this issue … other issue": each link type in both directions, or once
 * when both directions read the same (e.g. "relates to").
 */
export function linkOptions(types: readonly LinkType[]): LinkOption[] {
  return types.flatMap((t) => {
    const outward: LinkOption = { value: `${t.id}:OUTWARD`, label: t.outward, linkTypeId: t.id, direction: 'OUTWARD' };
    if (t.inward === t.outward) return [outward];
    return [outward, { value: `${t.id}:INWARD`, label: t.inward, linkTypeId: t.id, direction: 'INWARD' }];
  });
}

/** Groups links by their phrase ("blocks", "is blocked by", …), keeping the server's order. */
export function groupByDescription<T extends { description: string }>(links: readonly T[]): [string, T[]][] {
  const groups = new Map<string, T[]>();
  for (const link of links) {
    const group = groups.get(link.description);
    if (group) group.push(link);
    else groups.set(link.description, [link]);
  }
  return [...groups.entries()];
}

/** Normalizes a typed issue key ("bug-12 " → "BUG-12"); returns '' when it is not a key or id. */
export function normalizeIssueKey(input: string): string {
  const value = input.trim().toUpperCase();
  return /^[A-Z][A-Z0-9_]{1,9}-\d+$/.test(value) || /^\d+$/.test(value) ? value : '';
}
