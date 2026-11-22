// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { ActionIcon, Button, Group, MultiSelect, NumberInput, Paper, Select, Stack, Text, TextInput, Title } from '@mantine/core';
import { IconArrowDown, IconArrowUp, IconPlus, IconTrash } from '@tabler/icons-react';
import { useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { BoardConfigRequest, BoardConfigResponse, Swimlanes } from '../api/types';
import { moved } from '../lib/order';

export const SWIMLANES: Swimlanes[] = ['NONE', 'ASSIGNEE', 'EPIC', 'PRIORITY'];

interface ColumnDraft {
  /** Only for React keys: columns are replaced as a whole when saved. */
  uid: number;
  name: string;
  statusIds: string[];
}

let nextUid = 1;

/**
 * Edits the columns of a board (name and statuses, in order), its default lanes and how long
 * resolved issues stay on it. Used for the default board and for a project's own board.
 */
export function BoardConfigEditor({
  config,
  saving,
  readOnly,
  onSave,
  extraActions,
  allowCustomStatuses = false,
}: {
  config: BoardConfigResponse;
  saving: boolean;
  readOnly: boolean;
  onSave: (request: BoardConfigRequest) => void;
  extraActions?: ReactNode;
  allowCustomStatuses?: boolean;
}) {
  const { t } = useTranslation();
  const [swimlanes, setSwimlanes] = useState<Swimlanes>(config.swimlanes);
  const [resolvedDays, setResolvedDays] = useState<number>(config.resolvedDays);
  const [columns, setColumns] = useState<ColumnDraft[]>(() =>
    config.columns.map((c) => ({
      uid: nextUid++,
      name: c.name,
      statusIds: c.statusIds.map(String),
    })),
  );

  const [customStatuses, setCustomStatuses] = useState(() =>
    config.customStatusIds.map((id) => ({
      id,
      name: config.availableStatuses.find((status) => status.id === id)!.name,
    })),
  );
  const [replacements, setReplacements] = useState<Record<string, string>>({});

  const used = new Set(columns.flatMap((c) => c.statusIds));
  const removed = config.availableStatuses.filter((s) =>
    config.customStatusIds.includes(s.id) && !customStatuses.some((draft) => draft.id === s.id),
  );
  const statuses = [
    ...config.availableStatuses.filter((s) => !config.customStatusIds.includes(s.id)),
    ...customStatuses.filter((s) => s.name.trim()).map((s) => ({ ...s, category: 'IN_PROGRESS' })),
  ];
  const replacementOptions = statuses
    .filter((s) => s.category !== 'DONE' && used.has(String(s.id)))
    .map((s) => ({ value: String(s.id), label: s.name }));
  const unused = statuses.filter((s) => !used.has(String(s.id)));
  const names = columns.map((c) => c.name.trim().toLowerCase());
  const duplicate = (name: string) => name.trim() !== '' && names.filter((n) => n === name.trim().toLowerCase()).length > 1;
  const statusNames = statuses.map((s) => s.name.trim().toLowerCase());
  const duplicateStatus = (name: string) => statusNames.filter((n) => n === name.trim().toLowerCase()).length > 1;
  const valid = columns.length > 0 && columns.every((c) => c.name.trim() !== '' && !duplicate(c.name))
    && customStatuses.every((s) => s.name.trim() !== '' && !duplicateStatus(s.name));
  const change = (index: number, patch: Partial<ColumnDraft>) =>
    setColumns(columns.map((c, i) => (i === index ? { ...c, ...patch } : c)));

  return (
    <Stack>
      {allowCustomStatuses && (
        <Stack gap="xs">
          <Title order={4}>{t('board.config.customStatuses')}</Title>
          <Text size="sm" c="dimmed">{t('board.config.customStatusesHelp')}</Text>
          {customStatuses.map((status, index) => (
            <Group key={status.id} align="flex-end" gap="xs">
              <TextInput
                label={t('board.config.statusName')}
                value={status.name}
                maxLength={100}
                disabled={readOnly}
                onChange={(event) => {
                  const name = event.currentTarget.value;
                  setCustomStatuses((current) => current.map((s) => s.id === status.id ? { ...s, name } : s));
                }}
                error={duplicateStatus(status.name) ? t('board.config.nameDuplicate') : undefined}
              />
              {!readOnly && (
                <Group gap={4} pb={5}>
                  <ActionIcon variant="subtle" color="gray" disabled={index === 0} aria-label={t('board.config.moveUp')}
                    onClick={() => setCustomStatuses(moved(customStatuses, index, -1))}>
                    <IconArrowUp size={16} />
                  </ActionIcon>
                  <ActionIcon variant="subtle" color="gray" disabled={index === customStatuses.length - 1}
                    aria-label={t('board.config.moveDown')} onClick={() => setCustomStatuses(moved(customStatuses, index, 1))}>
                    <IconArrowDown size={16} />
                  </ActionIcon>
                  <ActionIcon variant="subtle" color="red" aria-label={t('board.config.removeStatus')}
                    onClick={() => {
                      setCustomStatuses((current) => current.filter((s) => s.id !== status.id));
                      setColumns((current) => current.map((column) => ({
                        ...column,
                        statusIds: column.statusIds.filter((id) => id !== String(status.id)),
                      })));
                    }}>
                    <IconTrash size={16} />
                  </ActionIcon>
                </Group>
              )}
            </Group>
          ))}
          {!readOnly && (
            <Group>
              <Button variant="default" leftSection={<IconPlus size={16} />} disabled={customStatuses.length >= 100}
                onClick={() => setCustomStatuses([...customStatuses, { id: -nextUid++, name: '' }])}>
                {t('board.config.addStatus')}
              </Button>
            </Group>
          )}
          {removed.map((status) => (
            <Select
              key={status.id}
              label={t('board.config.statusReplacement', { status: status.name })}
              description={t('board.config.statusReplacementHelp')}
              data={replacementOptions}
              value={replacementOptions.some((option) => option.value === replacements[status.id])
                ? replacements[status.id]! : null}
              onChange={(value) => setReplacements({ ...replacements, [status.id]: value ?? '' })}
              searchable
              clearable
            />
          ))}
        </Stack>
      )}
      {allowCustomStatuses && <Title order={4}>{t('board.config.columns')}</Title>}
      <Stack gap="xs">
        {columns.map((column, index) => (
          <Paper key={column.uid} withBorder p="sm">
            <Group align="flex-start" wrap="nowrap">
              <TextInput
                label={t('board.config.columnName')}
                value={column.name}
                onChange={(e) => change(index, { name: e.currentTarget.value })}
                error={column.name.trim() === '' ? t('board.config.nameRequired') : duplicate(column.name) ? t('board.config.nameDuplicate') : undefined}
                maxLength={100}
                disabled={readOnly}
                w={220}
              />
              <MultiSelect
                label={t('board.config.statuses')}
                data={statuses
                  .filter((s) => column.statusIds.includes(String(s.id)) || !used.has(String(s.id)))
                  .map((s) => ({ value: String(s.id), label: s.name }))}
                value={column.statusIds}
                onChange={(statusIds) => change(index, { statusIds })}
                placeholder={column.statusIds.length === 0 ? t('board.config.noStatuses') : undefined}
                disabled={readOnly}
                style={{ flex: 1 }}
                searchable
              />
              {!readOnly && (
                <Group gap={4} mt={24} wrap="nowrap">
                  <ActionIcon variant="subtle" color="gray" disabled={index === 0} aria-label={t('board.config.moveUp')}
                    onClick={() => setColumns(moved(columns, index, -1))}>
                    <IconArrowUp size={16} />
                  </ActionIcon>
                  <ActionIcon variant="subtle" color="gray" disabled={index === columns.length - 1}
                    aria-label={t('board.config.moveDown')} onClick={() => setColumns(moved(columns, index, 1))}>
                    <IconArrowDown size={16} />
                  </ActionIcon>
                  <ActionIcon variant="subtle" color="red" disabled={columns.length === 1}
                    aria-label={t('board.config.removeColumn')} onClick={() => setColumns(columns.filter((_, i) => i !== index))}>
                    <IconTrash size={16} />
                  </ActionIcon>
                </Group>
              )}
            </Group>
          </Paper>
        ))}
      </Stack>
      {unused.length > 0 && (
        <Text size="sm" c="dimmed">
          {t('board.config.unused', { statuses: unused.map((s) => s.name).join(', ') })}
        </Text>
      )}
      {!readOnly && (
        <Group>
          <Button variant="default" leftSection={<IconPlus size={16} />} disabled={columns.length >= 20}
            onClick={() => setColumns([...columns, { uid: nextUid++, name: '', statusIds: [] }])}>
            {t('board.config.addColumn')}
          </Button>
        </Group>
      )}
      <Group align="flex-end">
        <Select
          label={t('board.config.swimlanes')}
          description={t('board.config.swimlanesHelp')}
          data={SWIMLANES.map((s) => ({ value: s, label: t(`board.swimlanes.${s}`) }))}
          value={swimlanes}
          onChange={(v) => v && setSwimlanes(v as Swimlanes)}
          allowDeselect={false}
          disabled={readOnly}
          w={260}
        />
        <NumberInput
          label={t('board.config.resolvedDays')}
          description={t('board.config.resolvedDaysHelp')}
          value={resolvedDays}
          onChange={(v) => setResolvedDays(typeof v === 'number' ? v : 0)}
          min={0}
          max={365}
          clampBehavior="strict"
          allowDecimal={false}
          disabled={readOnly}
          w={260}
        />
      </Group>
      {!readOnly && (
        <Group>
          <Button
            loading={saving}
            disabled={!valid}
            onClick={() =>
              onSave({
                swimlanes,
                resolvedDays,
                columns: columns.map((c) => ({
                  name: c.name.trim(), statusIds: c.statusIds.map(Number),
                })),
                customStatuses: customStatuses.map((s) => ({ ...s, name: s.name.trim() })),
                removedStatusIds: removed.map((status) => status.id),
                statusReplacements: Object.fromEntries(removed.flatMap((status) => {
                  const replacement = replacements[status.id];
                  return replacementOptions.some((option) => option.value === replacement)
                    ? [[status.id, Number(replacement)]] : [];
                })),
              })
            }
          >
            {t('common.save')}
          </Button>
          {extraActions}
        </Group>
      )}
    </Stack>
  );
}
