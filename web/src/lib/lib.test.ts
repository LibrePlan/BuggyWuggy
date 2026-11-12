// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { fieldErrorMap, toApiError } from './apiError';
import { readCookie } from './cookies';
import { changedFields, formatBytes, formatDate } from './format';
import { moved } from './order';
import { childTypes, topLevelTypes } from './issueTypes';
import { hasConditions, paramsToQuery, queryToParams, sameQuery, type IssueQuery } from './issueQuery';
import { groupByDescription, linkOptions, normalizeIssueKey } from './links';
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

describe('issue links', () => {
  const types = [
    { id: 1, name: 'Blocks', outward: 'blocks', inward: 'is blocked by' },
    { id: 2, name: 'Relates', outward: 'relates to', inward: 'relates to' },
  ];
  it('offers both directions unless they read the same', () => {
    expect(linkOptions(types).map((o) => o.value)).toEqual(['1:OUTWARD', '1:INWARD', '2:OUTWARD']);
    expect(linkOptions(types)[1]).toMatchObject({ label: 'is blocked by', linkTypeId: 1, direction: 'INWARD' });
  });
  it('groups links by phrase in order', () => {
    const links = [
      { description: 'blocks', key: 'A-1' },
      { description: 'relates to', key: 'A-2' },
      { description: 'blocks', key: 'A-3' },
    ];
    expect(groupByDescription(links).map(([d, l]) => [d, l.map((x) => x.key)])).toEqual([
      ['blocks', ['A-1', 'A-3']],
      ['relates to', ['A-2']],
    ]);
  });
  it('normalizes typed keys', () => {
    expect(normalizeIssueKey(' bug-12 ')).toBe('BUG-12');
    expect(normalizeIssueKey('42')).toBe('42');
    expect(normalizeIssueKey('BUG-')).toBe('');
    expect(normalizeIssueKey('hello')).toBe('');
  });
});

describe('issue query in the URL', () => {
  it('round-trips a full query', () => {
    const query: IssueQuery = {
      text: 'login crash',
      projectIds: [3, 1],
      statusIds: [2],
      assignees: ['me', 'none', '42'],
      labels: ['ui'],
      resolution: 'UNRESOLVED',
      createdFrom: '2026-01-01',
      sort: 'PRIORITY',
      direction: 'DESC',
    };
    const params = queryToParams(query);
    expect(params.toString()).toBe(
      'q=login+crash&project=3%2C1&status=2&assignee=me%2Cnone%2C42&label=ui&resolution=unresolved' +
        '&createdFrom=2026-01-01&sort=priority&dir=desc',
    );
    expect(paramsToQuery(params)).toEqual(query);
  });
  it('ignores invalid values', () => {
    const query = paramsToQuery(
      new URLSearchParams('project=1,x&assignee=bob,me&resolution=maybe&sort=random&createdTo=yesterday&q=%20'),
    );
    expect(query).toEqual({ projectIds: [1], assignees: ['me'] });
  });
  it('compares queries regardless of list order and empty parts', () => {
    expect(sameQuery({ projectIds: [2, 1], labels: [] }, { projectIds: [1, 2], text: ' ' })).toBe(true);
    expect(sameQuery({ projectIds: [1] }, { projectIds: [1], sort: 'KEY' })).toBe(false);
  });
  it('knows whether there are conditions', () => {
    expect(hasConditions({ sort: 'KEY', labels: [] })).toBe(false);
    expect(hasConditions({ resolution: 'RESOLVED' })).toBe(true);
  });
});

describe('formatBytes', () => {
  it('uses readable units', () => {
    expect(formatBytes(0, 'en')).toBe('0 B');
    expect(formatBytes(512, 'en')).toBe('512 B');
    expect(formatBytes(1536, 'en')).toBe('1.5 KB');
    expect(formatBytes(50 * 1024 * 1024, 'en')).toBe('50 MB');
  });
});

describe('moved', () => {
  it('swaps with the neighbour and ignores moves past the ends', () => {
    expect(moved(['a', 'b', 'c'], 1, -1)).toEqual(['b', 'a', 'c']);
    expect(moved(['a', 'b', 'c'], 1, 1)).toEqual(['a', 'c', 'b']);
    expect(moved(['a', 'b'], 0, -1)).toEqual(['a', 'b']);
    expect(moved(['a', 'b'], 1, 1)).toEqual(['a', 'b']);
  });
});
