// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { formatUrgency, urgencyCalculation } from './urgency';

describe('urgency', () => {
  it('formats numbers like TaskWarrior', () => {
    expect(formatUrgency(2)).toBe('2');
    expect(formatUrgency(0.9)).toBe('0.9');
    expect(formatUrgency(33.30000001)).toBe('33.3');
    expect(formatUrgency(0.030137)).toBe('0.0301');
    expect(formatUrgency(-5)).toBe('-5');
  });

  it('shows the calculation as task info does', () => {
    expect(
      urgencyCalculation(2.9, [
        { name: 'tags', factor: 0.9, coefficient: 1, urgency: 0.9 },
        { name: 'age', factor: 1, coefficient: 2, urgency: 2 },
      ]),
    ).toBe(
      [
        'Urgency       2.9',
        '',
        '    tags    0.9 *    1 =    0.9',
        '    age       1 *    2 =      2',
        '                         ------',
        '                            2.9',
      ].join('\n'),
    );
  });
});
