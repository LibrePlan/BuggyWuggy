// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Avatar, Group, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import type { UserRef } from '../api/types';

export function initials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  const letters = parts.length > 1 ? parts[0]![0]! + parts[parts.length - 1]![0]! : (parts[0] ?? '?').slice(0, 2);
  return letters.toUpperCase();
}

/** A user with avatar, or "Unassigned"/"Nobody" when absent. */
export function UserLabel({ user, emptyLabel }: { user: UserRef | null | undefined; emptyLabel?: string }) {
  const { t } = useTranslation();
  if (!user) {
    return (
      <Text size="sm" c="dimmed">
        {emptyLabel ?? t('issue.unassigned')}
      </Text>
    );
  }
  return (
    <Group gap={6} wrap="nowrap" title={user.username}>
      <Avatar size={22} radius="xl" color="blue">
        {initials(user.displayName)}
      </Avatar>
      <Text size="sm" truncate>
        {user.displayName}
      </Text>
    </Group>
  );
}
