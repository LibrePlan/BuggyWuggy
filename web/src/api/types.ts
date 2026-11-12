// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import type { components } from './schema';

type Schemas = components['schemas'];

export type MyselfResponse = Schemas['MyselfResponse'];
export type SetupRequest = Schemas['SetupRequest'];
export type UserRef = Schemas['UserRef'];
export type ProjectResponse = Schemas['ProjectResponse'];
export type CreateProjectRequest = Schemas['CreateProjectRequest'];
export type ProjectPage = Schemas['PageResponseProjectResponse'];
export type ComponentResponse = Schemas['ComponentResponse'];
export type VersionResponse = Schemas['VersionResponse'];
export type IssueResponse = Schemas['IssueResponse'];
export type IssueSummaryResponse = Schemas['IssueSummaryResponse'];
export type IssuePage = Schemas['PageResponseIssueSummaryResponse'];
export type CreateIssueRequest = Schemas['CreateIssueRequest'];
export type UpdateIssueRequest = Schemas['UpdateIssueRequest'];
export type TransitionResponse = Schemas['TransitionResponse'];
export type TransitionRequest = Schemas['TransitionRequest'];
export type HistoryEntry = Schemas['Entry'];
export type HistoryItem = Schemas['Item'];
export type IssueTypeResponse = Schemas['IssueTypeResponse'];
export type IssueTypeRef = Schemas['IssueTypeRef'];
export type PriorityResponse = Schemas['PriorityResponse'];
export type PriorityRef = Schemas['PriorityRef'];
export type ResolutionResponse = Schemas['ResolutionResponse'];
export type StatusRef = Schemas['StatusRef'];
export type StatusCategory = StatusRef['category'];
export type ProjectPermission = Schemas['MyProjectPermissionsResponse']['permissions'][number];
export type CommentResponse = Schemas['CommentResponse'];
export type LinkTypeResponse = Schemas['LinkTypeResponse'];
export type IssueLinkResponse = Schemas['IssueLinkResponse'];
export type CreateLinkRequest = Schemas['CreateLinkRequest'];
export type WatchersResponse = Schemas['WatchersResponse'];
export type FilterResponse = Schemas['FilterResponse'];
export type CreateFilterRequest = Schemas['CreateFilterRequest'];
export type UpdateFilterRequest = Schemas['UpdateFilterRequest'];
export type SharedWith = FilterResponse['sharedWith'];
export type StatusResponse = Schemas['StatusResponse'];
export type AttachmentResponse = Schemas['AttachmentResponse'];
export type AttachmentSettings = Schemas['AttachmentSettingsResponse'];
export type UserResponse = Schemas['UserResponse'];
export type UserPage = Schemas['PageResponseUserResponse'];
export type CreateUserRequest = Schemas['CreateUserRequest'];
export type UpdateUserRequest = Schemas['UpdateUserRequest'];
export type UpdateMyselfRequest = Schemas['UpdateMyselfRequest'];
export type GroupRef = Schemas['GroupRef'];
export type GroupResponse = Schemas['GroupResponse'];
export type GroupPage = Schemas['PageResponseGroupResponse'];
export type GlobalPermissionResponse = Schemas['GlobalPermissionResponse'];
export type ProjectRoleMembers = Schemas['ProjectRoleMembersResponse'];
export type UpdateProjectRequest = Schemas['UpdateProjectRequest'];
export type CreateComponentRequest = Schemas['CreateComponentRequest'];
export type UpdateComponentRequest = Schemas['UpdateComponentRequest'];
export type CreateVersionRequest = Schemas['CreateVersionRequest'];
export type UpdateVersionRequest = Schemas['UpdateVersionRequest'];
