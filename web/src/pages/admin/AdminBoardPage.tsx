// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Stack, Text } from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { put } from '../../api/client';
import { keys, useDefaultBoardConfig } from '../../api/queries';
import type { BoardConfigRequest, BoardConfigResponse } from '../../api/types';
import { BoardConfigEditor } from '../../components/BoardConfigEditor';
import { ErrorAlert, PageLoader } from '../../components/QueryState';
import { notifyError, notifySaved } from '../../lib/notify';

/** The default board: the columns of every project that has no columns of its own. */
export function AdminBoardPage() {
  const { t } = useTranslation();
  const config = useDefaultBoardConfig();
  const queryClient = useQueryClient();
  const save = useMutation({
    mutationFn: (request: BoardConfigRequest) => put<BoardConfigResponse>('/api/v1/admin/board', request),
    onSuccess: (saved) => {
      queryClient.setQueryData(keys.defaultBoard, saved);
      // Every board without columns of its own changes.
      void queryClient.invalidateQueries({ queryKey: ['project'] });
      notifySaved();
    },
    onError: notifyError,
  });

  if (config.isPending) return <PageLoader />;
  if (config.isError) return <ErrorAlert error={config.error} />;
  return (
    <Stack>
      <Text size="sm" c="dimmed">
        {t('board.config.defaultIntro')}
      </Text>
      <BoardConfigEditor
        key={config.data.columns.map((c) => c.id).join(',')}
        config={config.data}
        saving={save.isPending}
        readOnly={false}
        onSave={(request) => save.mutate(request)}
      />
    </Stack>
  );
}
