// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

/** One term of TaskWarrior's urgency: factor × coefficient = urgency. */
export interface UrgencyTerm {
  name: string;
  factor: number;
  coefficient: number;
  urgency: number;
}

/** A number as TaskWarrior shows it: three significant digits, without trailing zeros (2, 0.9, 33.3). */
export function formatUrgency(value: number): string {
  if (value === 0) return '0';
  return String(Number(value.toPrecision(3)));
}

/**
 * The calculation as TaskWarrior's `task info` shows it:
 *
 *     Urgency       2.9
 *
 *         tags    0.9 *    1 =    0.9
 *         age       1 *    2 =      2
 *                              ------
 *                                 2.9
 */
export function urgencyCalculation(urgency: number, terms: UrgencyTerm[]): string {
  const rows = terms.map((t) => ({
    name: t.name,
    factor: formatUrgency(t.factor),
    coefficient: formatUrgency(t.coefficient),
    urgency: formatUrgency(t.urgency),
  }));
  const total = formatUrgency(urgency);
  const nameWidth = Math.max(4, ...rows.map((r) => r.name.length));
  const factorWidth = Math.max(6, ...rows.map((r) => r.factor.length + 1));
  const coefficientWidth = Math.max(4, ...rows.map((r) => r.coefficient.length + 1));
  const urgencyWidth = Math.max(6, total.length, ...rows.map((r) => r.urgency.length + 1));
  const lines = [`${'Urgency'.padEnd(14)}${total}`, ''];
  for (const r of rows) {
    lines.push(
      `    ${r.name.padEnd(nameWidth)} ${r.factor.padStart(factorWidth)} * ${r.coefficient.padStart(coefficientWidth)} = ${r.urgency.padStart(urgencyWidth)}`,
    );
  }
  const width = 4 + nameWidth + 1 + factorWidth + 3 + coefficientWidth + 3 + urgencyWidth;
  lines.push('-'.repeat(urgencyWidth).padStart(width));
  lines.push(total.padStart(width));
  return lines.join('\n');
}
