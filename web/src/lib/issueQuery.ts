// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { components } from '../api/schema';

export type IssueQuery = components['schemas']['IssueQuery'];
export type SortField = NonNullable<IssueQuery['sort']>;
export type SortDirection = NonNullable<IssueQuery['direction']>;

export const SORT_FIELDS: SortField[] = ['RELEVANCE', 'UPDATED', 'CREATED', 'KEY', 'PRIORITY', 'STATUS', 'DUE_DATE'];
const RESOLUTIONS = ['UNRESOLVED', 'RESOLVED'] as const;
const USER_VALUE = /^(me|none|\d{1,18})$/;
const DATE = /^\d{4}-\d{2}-\d{2}$/;

type IdListKey = 'projectIds' | 'issueTypeIds' | 'statusIds' | 'priorityIds';
type StringListKey = 'assignees' | 'reporters' | 'labels';
type DateKey = 'createdFrom' | 'createdTo' | 'updatedFrom' | 'updatedTo';

/** URL parameter names, short and readable: /issues?project=3&status=1,2&assignee=me */
const ID_PARAMS: [IdListKey, string][] = [
  ['projectIds', 'project'],
  ['issueTypeIds', 'type'],
  ['statusIds', 'status'],
  ['priorityIds', 'priority'],
];
const STRING_PARAMS: [StringListKey, string][] = [
  ['assignees', 'assignee'],
  ['reporters', 'reporter'],
  ['labels', 'label'],
];
const DATE_PARAMS: DateKey[] = ['createdFrom', 'createdTo', 'updatedFrom', 'updatedTo'];

/** Reads a query from URL parameters, ignoring anything invalid. */
export function paramsToQuery(params: URLSearchParams): IssueQuery {
  const query: IssueQuery = {};
  const text = params.get('q')?.trim();
  if (text) query.text = text;
  for (const [key, name] of ID_PARAMS) {
    const ids = list(params.get(name))
      .filter((v) => /^\d{1,18}$/.test(v))
      .map(Number);
    if (ids.length) query[key] = ids;
  }
  for (const [key, name] of STRING_PARAMS) {
    const values = list(params.get(name)).filter((v) => key === 'labels' || USER_VALUE.test(v));
    if (values.length) query[key] = values;
  }
  const resolution = params.get('resolution')?.toUpperCase();
  if (RESOLUTIONS.includes(resolution as (typeof RESOLUTIONS)[number])) {
    query.resolution = resolution as IssueQuery['resolution'];
  }
  for (const key of DATE_PARAMS) {
    const value = params.get(key);
    if (value && DATE.test(value)) query[key] = value;
  }
  const sort = params.get('sort')?.toUpperCase();
  if (SORT_FIELDS.includes(sort as SortField)) query.sort = sort as SortField;
  const dir = params.get('dir')?.toUpperCase();
  if (dir === 'ASC' || dir === 'DESC') query.direction = dir;
  return query;
}

/** Writes a query as URL parameters; the inverse of {@link paramsToQuery}. */
export function queryToParams(query: IssueQuery): URLSearchParams {
  const params = new URLSearchParams();
  if (query.text?.trim()) params.set('q', query.text.trim());
  for (const [key, name] of ID_PARAMS) {
    if (query[key]?.length) params.set(name, query[key]!.join(','));
  }
  for (const [key, name] of STRING_PARAMS) {
    if (query[key]?.length) params.set(name, query[key]!.join(','));
  }
  if (query.resolution) params.set('resolution', query.resolution.toLowerCase());
  for (const key of DATE_PARAMS) {
    if (query[key]) params.set(key, query[key]!);
  }
  if (query.sort) params.set('sort', query.sort.toLowerCase());
  if (query.direction) params.set('dir', query.direction.toLowerCase());
  return params;
}

/** Removes empty conditions and sorts lists, so equal queries compare equal. */
export function normalizeQuery(query: IssueQuery): IssueQuery {
  return paramsToQuery(queryToParams(sortLists(query)));
}

export function sameQuery(a: IssueQuery, b: IssueQuery): boolean {
  return queryToParams(normalizeQuery(a)).toString() === queryToParams(normalizeQuery(b)).toString();
}

/** Whether the query has any condition (sorting does not count). */
export function hasConditions(query: IssueQuery): boolean {
  const { sort: _sort, direction: _direction, ...conditions } = normalizeQuery(query);
  return Object.keys(conditions).length > 0;
}

function sortLists(query: IssueQuery): IssueQuery {
  const copy: IssueQuery = { ...query };
  for (const [key] of ID_PARAMS) if (copy[key]) copy[key] = [...copy[key]!].sort((x, y) => x - y);
  for (const [key] of STRING_PARAMS) if (copy[key]) copy[key] = [...new Set(copy[key])].sort();
  return copy;
}

function list(value: string | null): string[] {
  return value ? value.split(',').map((v) => v.trim()).filter(Boolean) : [];
}
