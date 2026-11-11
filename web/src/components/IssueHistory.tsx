// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Group, Stack, Table, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { useHistory } from '../api/queries';
import type { HistoryItem } from '../api/types';
import { formatDateTime } from '../lib/format';
import { ErrorAlert, PageLoader } from './QueryState';
import { UserLabel } from './UserLabel';

function shown(text: string | null, raw: string | null): string {
  return text ?? raw ?? '';
}

function Value({ value }: { value: string }) {
  return value ? (
    <Text size="sm" className="bw-pre-wrap" lineClamp={4}>
      {value}
    </Text>
  ) : (
    <Text size="sm" c="dimmed">
      —
    </Text>
  );
}

/** The change history of an issue, newest first. */
export function IssueHistory({ issueKey }: { issueKey: string }) {
  const { t, i18n } = useTranslation();
  const history = useHistory(issueKey);

  if (history.isPending) return <PageLoader />;
  if (history.isError) return <ErrorAlert error={history.error} />;
  if (history.data.length === 0) return <Text c="dimmed" size="sm">{t('history.empty')}</Text>;

  const entries = [...history.data].reverse();
  const fieldName = (item: HistoryItem) => t(`history.field.${item.field}`, { defaultValue: item.field });

  return (
    <Stack gap="lg">
      {entries.map((entry) => (
        <Stack key={entry.id} gap={6}>
          <Group gap={6}>
            <UserLabel user={entry.author} emptyLabel={t('history.system')} />
            <Text size="sm" c="dimmed">
              {t('history.madeChanges')} · {formatDateTime(entry.createdAt, i18n.language)}
            </Text>
          </Group>
          <Table withTableBorder={false} verticalSpacing={4} layout="fixed">
            <Table.Thead>
              <Table.Tr>
                <Table.Th w="20%">{t('history.fieldHeader')}</Table.Th>
                <Table.Th w="40%">{t('history.from')}</Table.Th>
                <Table.Th w="40%">{t('history.to')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {entry.items.map((item, index) => (
                <Table.Tr key={index}>
                  <Table.Td>
                    <Text size="sm" fw={500}>
                      {fieldName(item)}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Value value={shown(item.fromText, item.from)} />
                  </Table.Td>
                  <Table.Td>
                    <Value value={shown(item.toText, item.to)} />
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Stack>
      ))}
    </Stack>
  );
}
