// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { ApiError } from '../lib/apiError';
import { get, post, seg } from './client';
import type { IssueQuery } from '../lib/issueQuery';
import type {
  BoardConfigResponse,
  LicenseCountry,
  LicenseQuote,
  DonationResponse,
  LicenseStatus,
  BoardResponse,
  AttachmentResponse,
  AttachmentSettings,
  CommentResponse,
  FilterResponse,
  ImportJob,
  FailedMail,
  GlobalPermissionResponse,
  LanguageResponse,
  MailStatus,
  TemplateResponse,
  TemplateSummary,
  GroupPage,
  GroupRef,
  GroupResponse,
  ProjectRoleMembers,
  UserPage,
  UserResponse,
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
  UrgencyResponse,
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
  urgency: (key: string) => ['issue', key, 'urgency'] as const,
  board: (key: string) => ['project', key, 'board'] as const,
  boardConfig: (key: string) => ['project', key, 'board', 'config'] as const,
  defaultBoard: ['admin', 'board'] as const,
  license: ['admin', 'license'] as const,
  donation: ['donation'] as const,
  projectRoles: (key: string) => ['project', key, 'roles'] as const,
  admin: ['admin'] as const,
  adminUsers: (query: string, page: number) => ['admin', 'users', query, page] as const,
  adminUser: (id: number) => ['admin', 'user', id] as const,
  adminGroups: (query: string, page: number) => ['admin', 'groups', query, page] as const,
  adminGroup: (id: number) => ['admin', 'group', id] as const,
  groupMembers: (id: number, page: number) => ['admin', 'group', id, 'members', page] as const,
  globalPermissions: ['admin', 'global-permissions'] as const,
  languages: ['languages'] as const,
  adminLanguages: ['admin', 'languages'] as const,
  mailStatus: ['admin', 'mail', 'status'] as const,
  failedMails: ['admin', 'mail', 'failed'] as const,
  mailTemplates: ['admin', 'mail', 'templates'] as const,
  mailTemplate: (key: string, language: string) => ['admin', 'mail', 'templates', key, language] as const,
  importJobs: ['admin', 'import', 'jobs'] as const,
  importJob: (id: number) => ['admin', 'import', 'jobs', id] as const,
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

/** Projects the user can see; archived ones only on request (administrators see them). */
export function useProjects(query: string, page: number, includeArchived = false) {
  return useQuery({
    queryKey: [...keys.projects(query, page), includeArchived],
    queryFn: () => get<ProjectPage>('/api/v1/projects', { query, page, size: 50, includeArchived }),
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

/** TaskWarrior's urgency, with its calculation; {@code version} refetches it when the issue changed. */
export function useUrgency(key: string, version: string) {
  return useQuery({
    queryKey: [...keys.urgency(key), version],
    queryFn: () => get<UrgencyResponse>(`/api/v1/issues/${seg(key)}/urgency`),
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

export function useProjectRoles(key: string) {
  return useQuery({
    queryKey: keys.projectRoles(key),
    queryFn: () => get<ProjectRoleMembers[]>(`/api/v1/projects/${seg(key)}/roles`),
  });
}

/** Groups by name (needs the BROWSE_USERS permission). */
export function useGroupSearch(query: string, enabled: boolean) {
  return useQuery({
    queryKey: ['groups', 'picker', query],
    queryFn: () => get<GroupRef[]>('/api/v1/groups/picker', { query, limit: 20 }),
    enabled,
    placeholderData: keepPreviousData,
    retry: false,
  });
}

// --- administration -----------------------------------------------------------------

export function useAdminUsers(query: string, page: number) {
  return useQuery({
    queryKey: keys.adminUsers(query, page),
    queryFn: () => get<UserPage>('/api/v1/admin/users', { query, page, size: 50 }),
    placeholderData: keepPreviousData,
  });
}

export function useAdminUser(id: number) {
  return useQuery({ queryKey: keys.adminUser(id), queryFn: () => get<UserResponse>(`/api/v1/admin/users/${id}`) });
}

export function useAdminGroups(query: string, page: number) {
  return useQuery({
    queryKey: keys.adminGroups(query, page),
    queryFn: () => get<GroupPage>('/api/v1/admin/groups', { query, page, size: 50 }),
    placeholderData: keepPreviousData,
  });
}

export function useAdminGroup(id: number) {
  return useQuery({ queryKey: keys.adminGroup(id), queryFn: () => get<GroupResponse>(`/api/v1/admin/groups/${id}`) });
}

export function useGroupMembers(id: number, page: number) {
  return useQuery({
    queryKey: keys.groupMembers(id, page),
    queryFn: () => get<UserPage>(`/api/v1/admin/groups/${id}/members`, { page, size: 50 }),
    placeholderData: keepPreviousData,
  });
}

export function useGlobalPermissions() {
  return useQuery({
    queryKey: keys.globalPermissions,
    queryFn: () => get<GlobalPermissionResponse[]>('/api/v1/admin/global-permissions'),
  });
}

// --- languages and mail ---------------------------------------------------------------

/** Enabled languages, e.g. for the profile. */
export function useLanguages() {
  return useQuery({
    queryKey: keys.languages,
    queryFn: () => get<LanguageResponse[]>('/api/v1/languages'),
    staleTime: LOOKUP_STALE,
  });
}

export function useAdminLanguages() {
  return useQuery({ queryKey: keys.adminLanguages, queryFn: () => get<LanguageResponse[]>('/api/v1/admin/languages') });
}

export function useMailStatus() {
  return useQuery({ queryKey: keys.mailStatus, queryFn: () => get<MailStatus>('/api/v1/admin/mail/status') });
}

export function useFailedMails() {
  return useQuery({ queryKey: keys.failedMails, queryFn: () => get<FailedMail[]>('/api/v1/admin/mail/failed') });
}

export function useMailTemplates() {
  return useQuery({
    queryKey: keys.mailTemplates,
    queryFn: () => get<TemplateSummary[]>('/api/v1/admin/mail/templates'),
  });
}

export function useMailTemplate(key: string, language: string) {
  return useQuery({
    queryKey: keys.mailTemplate(key, language),
    queryFn: () => get<TemplateResponse>(`/api/v1/admin/mail/templates/${seg(key)}/${seg(language)}`),
  });
}

// --- import ---------------------------------------------------------------------------

/**
 * Refreshes every few seconds while an import runs, and now and then otherwise, so the
 * status line of the administration notices an import another administrator started.
 */
export function useImportJobs() {
  return useQuery({
    queryKey: keys.importJobs,
    queryFn: () => get<ImportJob[]>('/api/v1/admin/import/jobs'),
    refetchInterval: (query) => (query.state.data?.some((j) => j.status === 'RUNNING') ? 3000 : 30_000),
  });
}

export function useImportJob(id: number) {
  return useQuery({
    queryKey: keys.importJob(id),
    queryFn: () => get<ImportJob>(`/api/v1/admin/import/jobs/${id}`),
    refetchInterval: (query) => (query.state.data?.status === 'RUNNING' ? 1500 : false),
  });
}

export function useBoard(key: string) {
  return useQuery({
    queryKey: keys.board(key),
    queryFn: () => get<BoardResponse>(`/api/v1/projects/${seg(key)}/board`),
  });
}

/** The columns of a project's board, for its administrators. */
export function useBoardConfig(key: string) {
  return useQuery({
    queryKey: keys.boardConfig(key),
    queryFn: () => get<BoardConfigResponse>(`/api/v1/projects/${seg(key)}/board/config`),
  });
}

/** The default board, for administrators. */
export function useDefaultBoardConfig() {
  return useQuery({ queryKey: keys.defaultBoard, queryFn: () => get<BoardConfigResponse>('/api/v1/admin/board') });
}

/** Whether this installation supports the project with a donation (no login needed). */
export function useDonation() {
  return useQuery({ queryKey: keys.donation, queryFn: () => get<DonationResponse>('/api/v1/donation') });
}

export function useLicenseStatus() {
  return useQuery({ queryKey: keys.license, queryFn: () => get<LicenseStatus>('/api/v1/admin/license') });
}

export function useLicenseCountries() {
  return useQuery({
    queryKey: ['admin', 'license', 'countries'],
    queryFn: () => get<LicenseCountry[]>('/api/v1/admin/license/countries'),
    staleTime: LOOKUP_STALE,
  });
}

export function useLicenseQuote(country: string | null, users: number | null) {
  return useQuery({
    queryKey: ['admin', 'license', 'quote', country, users],
    queryFn: () => get<LicenseQuote>('/api/v1/admin/license/quote', { country, users }),
    enabled: country !== null && users !== null && users >= 1,
    placeholderData: keepPreviousData,
  });
}
