// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

export const EPIC = 1;
export const STANDARD = 0;
export const SUBTASK = -1;

interface Leveled {
  hierarchyLevel: number;
}

/** Issue types that can be created at the top level of a project (not sub-tasks). */
export function topLevelTypes<T extends Leveled>(types: readonly T[]): T[] {
  return types.filter((t) => t.hierarchyLevel >= STANDARD);
}

/**
 * Issue types a child of an issue of the given level can have: sub-tasks under a standard
 * issue, standard issues under an epic, nothing under a sub-task.
 */
export function childTypes<T extends Leveled>(types: readonly T[], parentLevel: number): T[] {
  if (parentLevel <= SUBTASK) return [];
  return types.filter((t) => t.hierarchyLevel === parentLevel - 1);
}
