// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Badge, Group, Text, ThemeIcon } from '@mantine/core';
import {
  IconBolt,
  IconBookmark,
  IconBug,
  IconBulbFilled,
  IconCheckbox,
  IconChevronDown,
  IconChevronUp,
  IconChevronsDown,
  IconChevronsUp,
  IconEqual,
  IconSubtask,
  IconSquare,
} from '@tabler/icons-react';
import type { ComponentType } from 'react';
import type { IssueTypeRef, PriorityRef, StatusCategory, StatusRef } from '../api/types';

type Icon = ComponentType<{ size?: number | string; color?: string; stroke?: number }>;

const TYPE_ICONS: Record<string, [Icon, string]> = {
  epic: [IconBolt, 'grape'],
  story: [IconBookmark, 'green'],
  feature: [IconBulbFilled, 'yellow'],
  task: [IconCheckbox, 'blue'],
  bug: [IconBug, 'red'],
  subtask: [IconSubtask, 'cyan'],
};

export function IssueTypeIcon({ type, size = 18 }: { type: Pick<IssueTypeRef, 'name' | 'icon'>; size?: number }) {
  const [Icon, color] = TYPE_ICONS[type.icon ?? ''] ?? [IconSquare, 'gray'];
  return (
    <ThemeIcon size={size} color={color} radius="sm" title={type.name} aria-label={type.name}>
      <Icon size={size * 0.75} />
    </ThemeIcon>
  );
}

const PRIORITY_ICONS: Record<string, Icon> = {
  highest: IconChevronsUp,
  high: IconChevronUp,
  medium: IconEqual,
  low: IconChevronDown,
  lowest: IconChevronsDown,
};

export function PriorityIcon({ priority, size = 18 }: { priority: Pick<PriorityRef, 'name' | 'icon' | 'color'>; size?: number }) {
  const Icon = PRIORITY_ICONS[priority.icon ?? ''] ?? IconEqual;
  return (
    <span title={priority.name} aria-label={priority.name} style={{ display: 'inline-flex' }}>
      <Icon size={size} color={priority.color ?? undefined} stroke={2.4} />
    </span>
  );
}

export function PriorityLabel({ priority }: { priority: PriorityRef | null }) {
  if (!priority) return <Text size="sm" c="dimmed">—</Text>;
  return (
    <Group gap={4} wrap="nowrap">
      <PriorityIcon priority={priority} />
      <Text size="sm">{priority.name}</Text>
    </Group>
  );
}

const CATEGORY_COLORS: Record<StatusCategory, string> = { TODO: 'gray', IN_PROGRESS: 'blue', DONE: 'green' };

export function StatusBadge({ status, size = 'sm' }: { status: StatusRef; size?: 'xs' | 'sm' | 'md' | 'lg' }) {
  return (
    <Badge color={CATEGORY_COLORS[status.category]} variant="light" size={size} radius="sm">
      {status.name}
    </Badge>
  );
}
