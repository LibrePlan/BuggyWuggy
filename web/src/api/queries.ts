// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { ApiError } from '../lib/apiError';
import { get, post, seg } from './client';
import type { IssueQuery } from '../lib/issueQuery';
import type {
  AttachmentResponse,
  AttachmentSettings,
  CommentResponse,
  FilterResponse,
  StatusResponse,
  ComponentResponse,
  IssueLinkResponse,
  LinkTypeResponse,
  WatchersResponse,
  HistoryEntry,
  IssuePage,
  IssueResponse,
  IssueTypeResponse,
  MyselfResponse,
  PriorityResponse,
  ProjectPage,
  ProjectPermission,
  ProjectResponse,
  ResolutionResponse,
  TransitionResponse,
  UserRef,
  VersionResponse,
} from './types';

/** Query keys, so mutations can invalidate exactly what they changed. */
export const keys = {
  setup: ['setup'] as const,
  myself: ['myself'] as const,
  projects: (query: string, page: number) => ['projects', query, page] as const,
  allProjects: ['projects'] as const,
  project: (key: string) => ['project', key] as const,
  projectPermissions: (key: string) => ['project', key, 'permissions'] as const,
  projectIssues: (key: string, page: number) => ['project', key, 'issues', page] as const,
  components: (key: string) => ['project', key, 'components'] as const,
  versions: (key: string) => ['project', key, 'versions'] as const,
  assignable: (key: string, query: string) => ['project', key, 'assignable', query] as const,
  issue: (key: string) => ['issue', key] as const,
  transitions: (key: string) => ['issue', key, 'transitions'] as const,
  history: (key: string) => ['issue', key, 'history'] as const,
  comments: (key: string) => ['issue', key, 'comments'] as const,
  links: (key: string) => ['issue', key, 'links'] as const,
  watchers: (key: string) => ['issue', key, 'watchers'] as const,
  attachments: (key: string) => ['issue', key, 'attachments'] as const,
  search: (query: IssueQuery, page: number, size: number) => ['search', query, page, size] as const,
  allSearches: ['search'] as const,
  filters: ['filters'] as const,
  filter: (id: number) => ['filters', id] as const,
};

const LOOKUP_STALE = 5 * 60_000;

export function useSetupStatus() {
  return useQuery({
    queryKey: keys.setup,
    queryFn: () => get<{ setupRequired: boolean }>('/api/v1/setup'),
    staleTime: Infinity,
  });
}

/** The logged-in user, or {@code null} when nobody is logged in. */
export function useMyself() {
  return useQuery({
    queryKey: keys.myself,
    queryFn: async () => {
      try {
        return await get<MyselfResponse>('/api/v1/myself');
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) return null;
        throw e;
      }
    },
    staleTime: 60_000,
  });
}

export function useProjects(query: string, page: number) {
  return useQuery({
    queryKey: keys.projects(query, page),
    queryFn: () => get<ProjectPage>('/api/v1/projects', { query, page, size: 50 }),
    placeholderData: keepPreviousData,
  });
}

export function useProject(key: string) {
  return useQuery({ queryKey: keys.project(key), queryFn: () => get<ProjectResponse>(`/api/v1/projects/${seg(key)}`) });
}

export function useProjectPermissions(key: string | undefined) {
  return useQuery({
    queryKey: keys.projectPermissions(key ?? ''),
    queryFn: async () => {
      const r = await get<{ permissions: ProjectPermission[] }>(`/api/v1/projects/${seg(key!)}/my-permissions`);
      return new Set(r.permissions);
    },
    enabled: !!key,
  });
}

export function useProjectIssues(key: string, page: number, size = 50) {
  return useQuery({
    queryKey: keys.projectIssues(key, page),
    queryFn: () => get<IssuePage>(`/api/v1/projects/${seg(key)}/issues`, { page, size }),
    placeholderData: keepPreviousData,
  });
}

export function useComponents(key: string | undefined) {
  return useQuery({
    queryKey: keys.components(key ?? ''),
    queryFn: () => get<ComponentResponse[]>(`/api/v1/projects/${seg(key!)}/components`),
    enabled: !!key,
  });
}

export function useVersions(key: string | undefined) {
  return useQuery({
    queryKey: keys.versions(key ?? ''),
    queryFn: () => get<VersionResponse[]>(`/api/v1/projects/${seg(key!)}/versions`),
    enabled: !!key,
  });
}

export function useAssignableUsers(key: string | undefined, query: string, enabled = true) {
  return useQuery({
    queryKey: keys.assignable(key ?? '', query),
    queryFn: () => get<UserRef[]>(`/api/v1/projects/${seg(key!)}/assignable-users`, { query, limit: 20 }),
    enabled: enabled && !!key,
    placeholderData: keepPreviousData,
  });
}

export function useIssue(key: string) {
  return useQuery({ queryKey: keys.issue(key), queryFn: () => get<IssueResponse>(`/api/v1/issues/${seg(key)}`) });
}

export function useTransitions(key: string, enabled: boolean) {
  return useQuery({
    queryKey: keys.transitions(key),
    queryFn: () => get<TransitionResponse[]>(`/api/v1/issues/${seg(key)}/transitions`),
    enabled,
  });
}

export function useHistory(key: string) {
  return useQuery({
    queryKey: keys.history(key),
    queryFn: () => get<HistoryEntry[]>(`/api/v1/issues/${seg(key)}/history`),
  });
}

export function useIssueTypes() {
  return useQuery({
    queryKey: ['lookup', 'issue-types'],
    queryFn: () => get<IssueTypeResponse[]>('/api/v1/issue-types'),
    staleTime: LOOKUP_STALE,
  });
}

export function usePriorities() {
  return useQuery({
    queryKey: ['lookup', 'priorities'],
    queryFn: () => get<PriorityResponse[]>('/api/v1/priorities'),
    staleTime: LOOKUP_STALE,
  });
}

export function useResolutions() {
  return useQuery({
    queryKey: ['lookup', 'resolutions'],
    queryFn: () => get<ResolutionResponse[]>('/api/v1/resolutions'),
    staleTime: LOOKUP_STALE,
  });
}

export function useComments(key: string) {
  return useQuery({
    queryKey: keys.comments(key),
    queryFn: () => get<CommentResponse[]>(`/api/v1/issues/${seg(key)}/comments`),
  });
}

export function useLinks(key: string) {
  return useQuery({
    queryKey: keys.links(key),
    queryFn: () => get<IssueLinkResponse[]>(`/api/v1/issues/${seg(key)}/links`),
  });
}

export function useWatchers(key: string) {
  return useQuery({
    queryKey: keys.watchers(key),
    queryFn: () => get<WatchersResponse>(`/api/v1/issues/${seg(key)}/watchers`),
  });
}

export function useLinkTypes() {
  return useQuery({
    queryKey: ['lookup', 'link-types'],
    queryFn: () => get<LinkTypeResponse[]>('/api/v1/issue-link-types'),
    staleTime: LOOKUP_STALE,
  });
}

export function useStatuses() {
  return useQuery({
    queryKey: ['lookup', 'statuses'],
    queryFn: () => get<StatusResponse[]>('/api/v1/statuses'),
    staleTime: LOOKUP_STALE,
  });
}

/** All projects the user can see, for pickers (up to 200). */
export function useAllProjects() {
  return useQuery({
    queryKey: ['projects', 'all'],
    queryFn: () => get<ProjectPage>('/api/v1/projects', { size: 200 }),
    select: (page) => page.items,
    staleTime: 60_000,
  });
}

export function useIssueSearch(query: IssueQuery, page: number, size = 50, enabled = true) {
  return useQuery({
    queryKey: keys.search(query, page, size),
    queryFn: () => post<IssuePage>(`/api/v1/issues/search?page=${page}&size=${size}`, query),
    placeholderData: keepPreviousData,
    enabled,
  });
}

export function useFilters() {
  return useQuery({ queryKey: keys.filters, queryFn: () => get<FilterResponse[]>('/api/v1/filters') });
}

export function useFilter(id: number | null) {
  return useQuery({
    queryKey: keys.filter(id ?? 0),
    queryFn: () => get<FilterResponse>(`/api/v1/filters/${id}`),
    enabled: id !== null,
  });
}

/** Users by name (needs the BROWSE_USERS permission; otherwise the query fails with 403). */
export function useUserSearch(query: string, enabled: boolean) {
  return useQuery({
    queryKey: ['users', 'picker', query],
    queryFn: () => get<UserRef[]>('/api/v1/users/picker', { query, limit: 20 }),
    enabled,
    placeholderData: keepPreviousData,
    retry: false,
  });
}

export function useUsersByIds(ids: number[]) {
  const sorted = [...ids].sort((a, b) => a - b);
  return useQuery({
    queryKey: ['users', 'ids', sorted],
    queryFn: () => get<UserRef[]>('/api/v1/users/picker', { ids: sorted.join(','), limit: 100 }),
    enabled: sorted.length > 0,
    staleTime: 5 * 60_000,
    retry: false,
  });
}

export function useLabelSuggestions(query: string, enabled: boolean) {
  return useQuery({
    queryKey: ['labels', query],
    queryFn: () => get<string[]>('/api/v1/labels', { query, limit: 20 }),
    enabled,
    placeholderData: keepPreviousData,
  });
}

export function useAttachments(key: string) {
  return useQuery({
    queryKey: keys.attachments(key),
    queryFn: () => get<AttachmentResponse[]>(`/api/v1/issues/${seg(key)}/attachments`),
  });
}

export function useAttachmentSettings() {
  return useQuery({
    queryKey: ['lookup', 'attachment-settings'],
    queryFn: () => get<AttachmentSettings>('/api/v1/attachments/settings'),
    staleTime: Infinity,
  });
}
