// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Alert, Anchor, Button, Stack, Text } from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { del, put, seg } from '../../api/client';
import { keys, useBoardConfig } from '../../api/queries';
import type { BoardConfigRequest, BoardConfigResponse } from '../../api/types';
import { notifyError, notifySaved } from '../../lib/notify';
import { BoardConfigEditor } from '../BoardConfigEditor';
import { ErrorAlert, PageLoader } from '../QueryState';

/** The columns of the project's board: the default board, or columns of its own. */
export function BoardSettings({ projectKey, readOnly }: { projectKey: string; readOnly: boolean }) {
  const { t } = useTranslation();
  const config = useBoardConfig(projectKey);
  const queryClient = useQueryClient();
  const base = `/api/v1/projects/${seg(projectKey)}/board/config`;
  const done = (saved: BoardConfigResponse) => {
    queryClient.setQueryData(keys.boardConfig(projectKey), saved);
    void queryClient.invalidateQueries({ queryKey: keys.board(projectKey), exact: true });
    notifySaved();
  };
  const save = useMutation({
    mutationFn: (request: BoardConfigRequest) => put<BoardConfigResponse>(base, request),
    onSuccess: done,
    onError: notifyError,
  });
  const reset = useMutation({ mutationFn: () => del<BoardConfigResponse>(base), onSuccess: done, onError: notifyError });

  if (config.isPending) return <PageLoader />;
  if (config.isError) return <ErrorAlert error={config.error} />;
  const c = config.data;

  return (
    <Stack>
      <Text size="sm" c="dimmed">
        {t('board.config.projectIntro')}{' '}
        <Anchor component={Link} to={`/projects/${projectKey}/board`} size="sm">
          {t('board.open')}
        </Anchor>
      </Text>
      {!c.own && (
        <Alert color="blue" variant="light">
          {t('board.config.usesDefault')}
        </Alert>
      )}
      <BoardConfigEditor
        key={`${c.own}-${c.columns.map((x) => x.id).join(',')}`}
        config={c}
        saving={save.isPending}
        readOnly={readOnly}
        onSave={(request) => save.mutate(request)}
        extraActions={
          c.own && (
            <Button variant="subtle" color="gray" loading={reset.isPending} onClick={() => reset.mutate()}>
              {t('board.config.useDefault')}
            </Button>
          )
        }
      />
    </Stack>
  );
}
