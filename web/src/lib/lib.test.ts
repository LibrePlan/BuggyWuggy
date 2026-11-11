// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { fieldErrorMap, toApiError } from './apiError';
import { readCookie } from './cookies';
import { changedFields, formatDate } from './format';
import { childTypes, topLevelTypes } from './issueTypes';
import { suggestProjectKey } from './projectKey';
import { withQuery } from '../api/client';

describe('readCookie', () => {
  it('finds a cookie among others and decodes it', () => {
    expect(readCookie('a=1; XSRF-TOKEN=abc%3D; b=2', 'XSRF-TOKEN')).toBe('abc=');
  });
  it('returns undefined when absent', () => {
    expect(readCookie('a=1', 'XSRF-TOKEN')).toBeUndefined();
    expect(readCookie('', 'XSRF-TOKEN')).toBeUndefined();
  });
  it('does not match on a name suffix', () => {
    expect(readCookie('MY-XSRF-TOKEN=x', 'XSRF-TOKEN')).toBeUndefined();
  });
});

describe('toApiError', () => {
  it('reads a problem detail with code and field errors', () => {
    const e = toApiError(400, {
      title: 'Bad Request',
      detail: 'Invalid request',
      code: 'validation.failed',
      errors: [{ field: 'summary', message: 'must not be blank' }],
    });
    expect(e.status).toBe(400);
    expect(e.message).toBe('Invalid request');
    expect(e.code).toBe('validation.failed');
    expect(fieldErrorMap(e)).toEqual({ summary: 'must not be blank' });
  });
  it('copes with a body that is not a problem detail', () => {
    const e = toApiError(502, undefined);
    expect(e.message).toBe('HTTP 502');
    expect(e.code).toBeUndefined();
    expect(fieldErrorMap(new Error('x'))).toEqual({});
  });
});

describe('suggestProjectKey', () => {
  it('uses initials of multiple words', () => {
    expect(suggestProjectKey('Customer Portal')).toBe('CP');
    expect(suggestProjectKey('the big rewrite project')).toBe('TBRP');
  });
  it('uses the start of a single word', () => {
    expect(suggestProjectKey('Website')).toBe('WEB');
  });
  it('strips accents and skips leading digits', () => {
    expect(suggestProjectKey('Équipe')).toBe('EQU');
    expect(suggestProjectKey('2026 Roadmap')).toBe('ROA');
  });
  it('returns empty when nothing usable', () => {
    expect(suggestProjectKey('')).toBe('');
    expect(suggestProjectKey('!!! 42')).toBe('');
  });
});

describe('issue type hierarchy', () => {
  const types = [
    { name: 'Epic', hierarchyLevel: 1 },
    { name: 'Task', hierarchyLevel: 0 },
    { name: 'Sub-task', hierarchyLevel: -1 },
  ];
  it('lists top-level types', () => {
    expect(topLevelTypes(types).map((t) => t.name)).toEqual(['Epic', 'Task']);
  });
  it('lists child types per parent level', () => {
    expect(childTypes(types, 1).map((t) => t.name)).toEqual(['Task']);
    expect(childTypes(types, 0).map((t) => t.name)).toEqual(['Sub-task']);
    expect(childTypes(types, -1)).toEqual([]);
  });
});

describe('changedFields', () => {
  it('keeps only changed values, comparing lists regardless of order', () => {
    const current = { summary: 'A', labels: ['x', 'y'], priorityId: 3, description: null as string | null };
    expect(changedFields(current, { summary: 'A', labels: ['y', 'x'], priorityId: 4, description: null })).toEqual({
      priorityId: 4,
    });
  });
  it('treats undefined and null as equal', () => {
    expect(changedFields({ a: null as string | null }, { a: undefined })).toEqual({});
  });
});

describe('formatting', () => {
  it('formats a date without a time zone shift', () => {
    expect(formatDate('2026-03-01', 'en-GB')).toBe('1 Mar 2026');
    expect(formatDate(null, 'en-GB')).toBe('');
  });
  it('builds query strings without empty values', () => {
    expect(withQuery('/x', { a: 1, b: '', c: undefined, d: 'q r' })).toBe('/x?a=1&d=q+r');
    expect(withQuery('/x')).toBe('/x');
  });
});
