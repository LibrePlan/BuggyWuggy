// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Button, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconEye, IconEyeOff } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { del, put, seg } from '../api/client';
import { keys, useMyself, useWatchers } from '../api/queries';
import type { WatchersResponse } from '../api/types';

/** Starts or stops watching the issue; the count is shown, and the names when permitted. */
export function WatchButton({ issueKey }: { issueKey: string }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const myself = useMyself().data;
  const watchers = useWatchers(issueKey).data;

  const toggle = useMutation({
    mutationFn: (watch: boolean) => {
      const path = `/api/v1/issues/${seg(issueKey)}/watchers/${myself!.id}`;
      return watch ? put<WatchersResponse>(path) : del<WatchersResponse>(path);
    },
    onSuccess: (data) => queryClient.setQueryData(keys.watchers(issueKey), data),
    onError: (e) => notifications.show({ color: 'red', title: t('error.title'), message: e.message }),
  });

  if (!watchers || !myself) return null;
  const names = watchers.watchers?.map((w) => w.displayName).join(', ');
  const label = watchers.watching ? t('watch.stop') : t('watch.start');

  return (
    <Tooltip label={names || t('watch.count', { count: watchers.count })} withArrow>
      <Button
        variant={watchers.watching ? 'light' : 'default'}
        leftSection={watchers.watching ? <IconEyeOff size={16} /> : <IconEye size={16} />}
        rightSection={<span aria-label={t('watch.count', { count: watchers.count })}>{watchers.count}</span>}
        loading={toggle.isPending}
        onClick={() => toggle.mutate(!watchers.watching)}
      >
        {label}
      </Button>
    </Tooltip>
  );
}
