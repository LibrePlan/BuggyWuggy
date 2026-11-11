// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { notifications } from '@mantine/notifications';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { ApiError } from '../lib/apiError';
import { patch, post, seg } from './client';
import { keys } from './queries';
import type { IssueResponse, TransitionRequest, UpdateIssueRequest } from './types';

/**
 * Changes to one issue. Both send the version the user has seen; when someone else changed
 * the issue in the meantime, the server answers 409 and the latest version is loaded.
 */
export function useIssueMutations(issue: IssueResponse | undefined) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();

  function onSuccess(updated: IssueResponse) {
    queryClient.setQueryData(keys.issue(updated.key), updated);
    void queryClient.invalidateQueries({ queryKey: keys.history(updated.key) });
    void queryClient.invalidateQueries({ queryKey: keys.transitions(updated.key) });
    void queryClient.invalidateQueries({ queryKey: ['project', updated.project.key, 'issues'] });
    if (updated.parent) void queryClient.invalidateQueries({ queryKey: keys.issue(updated.parent.key) });
  }

  function onError(error: Error) {
    if (error instanceof ApiError && error.status === 409 && issue) {
      notifications.show({ color: 'orange', title: t('issue.conflictTitle'), message: t('issue.conflict') });
      void queryClient.invalidateQueries({ queryKey: ['issue', issue.key] });
    } else if (!(error instanceof ApiError && error.fieldErrors.length > 0)) {
      notifications.show({ color: 'red', title: t('error.title'), message: error.message });
    }
  }

  const update = useMutation({
    mutationFn: (changes: Omit<UpdateIssueRequest, 'version'>) =>
      patch<IssueResponse>(`/api/v1/issues/${seg(issue!.key)}`, { ...changes, version: issue!.version }),
    onSuccess,
    onError,
  });

  const transition = useMutation({
    mutationFn: (request: Omit<TransitionRequest, 'version'>) =>
      post<IssueResponse>(`/api/v1/issues/${seg(issue!.key)}/transitions`, { ...request, version: issue!.version }),
    onSuccess,
    onError,
  });

  return { update, transition };
}
