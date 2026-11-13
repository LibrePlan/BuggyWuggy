// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Code, Loader, Popover, Stack, Text, UnstyledButton } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { useUrgency } from '../api/queries';
import { formatUrgency, urgencyCalculation } from '../lib/urgency';

/**
 * TaskWarrior's urgency of an issue; a click shows how it was calculated. {@code version}
 * (e.g. the issue's updatedAt and its number of links) makes it recalculate after changes.
 */
export function IssueUrgency({ issueKey, version }: { issueKey: string; version: string }) {
  const { t } = useTranslation();
  const urgency = useUrgency(issueKey, version);
  if (urgency.isPending) return <Loader size="xs" />;
  if (urgency.isError) return <Text size="sm" c="dimmed">–</Text>;
  const data = urgency.data;
  return (
    <Popover position="bottom-start" withArrow shadow="md" withinPortal>
      <Popover.Target>
        <UnstyledButton aria-label={t('taskwarrior.showCalculation')} title={t('taskwarrior.showCalculation')}>
          <Text size="sm" fw={600} td="underline" style={{ textDecorationStyle: 'dotted' }}>
            {data.urgency == null ? '–' : formatUrgency(data.urgency)}
          </Text>
        </UnstyledButton>
      </Popover.Target>
      <Popover.Dropdown>
        {data.urgency == null ? (
          <Text size="sm" maw={280}>
            {t('taskwarrior.resolved')}
          </Text>
        ) : (
          <Stack gap="xs">
            <Code block>{urgencyCalculation(data.urgency, data.terms)}</Code>
            {data.terms.length === 0 && (
              <Text size="xs" c="dimmed">
                {t('taskwarrior.noTerms')}
              </Text>
            )}
            <Text size="xs" c="dimmed" maw={360}>
              {t('taskwarrior.explanation')}
            </Text>
          </Stack>
        )}
      </Popover.Dropdown>
    </Popover>
  );
}
