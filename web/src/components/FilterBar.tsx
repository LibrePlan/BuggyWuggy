// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { ActionIcon, Button, Group, MultiSelect, Popover, Select, SimpleGrid, Stack, TextInput, Tooltip } from '@mantine/core';
import { useDebouncedCallback } from '@mantine/hooks';
import { IconAdjustmentsHorizontal, IconFilterOff, IconSearch, IconSortAscending, IconSortDescending } from '@tabler/icons-react';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAllProjects, useIssueTypes, usePriorities, useStatuses } from '../api/queries';
import { hasConditions, SORT_FIELDS, type IssueQuery, type SortField } from '../lib/issueQuery';
import { LabelFilterInput } from './LabelFilterInput';
import { UserFilterSelect } from './UserFilterSelect';

const ids = (values: string[]) => values.map(Number);
const strs = (values: number[] | undefined) => (values ?? []).map(String);

/** The conditions of an issue search. Every change is reported at once (text debounced). */
export function FilterBar({ query, onChange }: { query: IssueQuery; onChange: (query: IssueQuery) => void }) {
  const { t } = useTranslation();
  const projects = useAllProjects().data ?? [];
  const types = useIssueTypes().data ?? [];
  const statuses = useStatuses().data ?? [];
  const priorities = usePriorities().data ?? [];
  const [text, setText] = useState(query.text ?? '');
  const [moreOpen, setMoreOpen] = useState(false);

  // Follow outside changes (back button, loading a filter).
  useEffect(() => setText(query.text ?? ''), [query.text]);
  const commitText = useDebouncedCallback((value: string) => onChange({ ...query, text: value || undefined }), 350);

  const set = <K extends keyof IssueQuery>(key: K, value: IssueQuery[K]) => onChange({ ...query, [key]: value });
  const moreCount = [query.reporters?.length, query.createdFrom, query.createdTo, query.updatedFrom, query.updatedTo]
    .filter(Boolean).length;
  const sort: SortField = query.sort ?? (query.text ? 'RELEVANCE' : 'UPDATED');
  const descending = (query.direction ?? (sort === 'KEY' ? 'ASC' : 'DESC')) === 'DESC';

  return (
    <Stack gap="xs">
      <Group align="flex-end" gap="xs" wrap="wrap">
        <TextInput
          label={t('filter.text')}
          placeholder={t('filter.textPlaceholder')}
          leftSection={<IconSearch size={16} />}
          value={text}
          onChange={(e) => {
            setText(e.currentTarget.value);
            commitText(e.currentTarget.value.trim());
          }}
          onKeyDown={(e) => {
            if (e.key === 'Enter') {
              commitText.flush();
            }
          }}
          w={260}
        />
        <Select
          label={t('filter.sort')}
          data={SORT_FIELDS.map((f) => ({ value: f, label: t(`filter.sortField.${f}`) }))}
          value={sort}
          onChange={(v) => set('sort', (v as SortField) ?? undefined)}
          allowDeselect={false}
          w={170}
        />
        <Tooltip label={descending ? t('filter.descending') : t('filter.ascending')}>
          <ActionIcon
            variant="default"
            size="input-sm"
            aria-label={descending ? t('filter.descending') : t('filter.ascending')}
            onClick={() => set('direction', descending ? 'ASC' : 'DESC')}
          >
            {descending ? <IconSortDescending size={18} /> : <IconSortAscending size={18} />}
          </ActionIcon>
        </Tooltip>
        {hasConditions(query) && (
          <Button
            variant="subtle"
            color="gray"
            leftSection={<IconFilterOff size={16} />}
            onClick={() => onChange({ sort: query.sort, direction: query.direction })}
          >
            {t('filter.clear')}
          </Button>
        )}
      </Group>
      <SimpleGrid cols={{ base: 1, xs: 2, md: 4, xl: 8 }} spacing="xs" verticalSpacing="xs">
        <MultiSelect
          label={t('issue.project')}
          data={projects.map((p) => ({ value: String(p.id), label: p.name }))}
          value={strs(query.projectIds)}
          onChange={(v) => set('projectIds', ids(v))}
          searchable
          clearable
        />
        <MultiSelect
          label={t('issue.type')}
          data={types.map((x) => ({ value: String(x.id), label: x.name }))}
          value={strs(query.issueTypeIds)}
          onChange={(v) => set('issueTypeIds', ids(v))}
          clearable
        />
        <MultiSelect
          label={t('issue.status')}
          data={statuses.map((x) => ({ value: String(x.id), label: x.name }))}
          value={strs(query.statusIds)}
          onChange={(v) => set('statusIds', ids(v))}
          clearable
        />
        <MultiSelect
          label={t('issue.priority')}
          data={priorities.map((x) => ({ value: String(x.id), label: x.name }))}
          value={strs(query.priorityIds)}
          onChange={(v) => set('priorityIds', ids(v))}
          clearable
        />
        <UserFilterSelect
          label={t('issue.assignee')}
          noneLabel={t('issue.unassigned')}
          value={query.assignees ?? []}
          onChange={(v) => set('assignees', v)}
        />
        <Select
          label={t('issue.resolution')}
          data={[
            { value: 'UNRESOLVED', label: t('filter.unresolved') },
            { value: 'RESOLVED', label: t('filter.resolved') },
          ]}
          placeholder={t('filter.any')}
          value={query.resolution ?? null}
          onChange={(v) => set('resolution', (v as IssueQuery['resolution']) ?? undefined)}
          clearable
        />
        <LabelFilterInput label={t('issue.labels')} value={query.labels ?? []} onChange={(v) => set('labels', v)} />
        <Popover opened={moreOpen} onChange={setMoreOpen} position="bottom-end" width={340} withinPortal trapFocus>
          <Popover.Target>
            <Button
              variant={moreCount ? 'light' : 'default'}
              mt={{ base: 0, xl: 24 }}
              leftSection={<IconAdjustmentsHorizontal size={16} />}
              onClick={() => setMoreOpen((o) => !o)}
            >
              {moreCount ? t('filter.moreCount', { count: moreCount }) : t('filter.more')}
            </Button>
          </Popover.Target>
          <Popover.Dropdown>
            <Stack gap="xs">
              <UserFilterSelect
                label={t('issue.reporter')}
                noneLabel={t('filter.nobody')}
                value={query.reporters ?? []}
                onChange={(v) => set('reporters', v)}
              />
              <DateRange
                label={t('issue.created')}
                from={query.createdFrom}
                to={query.createdTo}
                onChange={(from, to) => onChange({ ...query, createdFrom: from, createdTo: to })}
              />
              <DateRange
                label={t('issue.updated')}
                from={query.updatedFrom}
                to={query.updatedTo}
                onChange={(from, to) => onChange({ ...query, updatedFrom: from, updatedTo: to })}
              />
            </Stack>
          </Popover.Dropdown>
        </Popover>
      </SimpleGrid>
    </Stack>
  );
}

function DateRange({
  label,
  from,
  to,
  onChange,
}: {
  label: string;
  from: string | undefined;
  to: string | undefined;
  onChange: (from: string | undefined, to: string | undefined) => void;
}) {
  const { t } = useTranslation();
  return (
    <Group grow gap="xs">
      <TextInput
        type="date"
        label={`${label} ${t('filter.from')}`}
        value={from ?? ''}
        onChange={(e) => onChange(e.currentTarget.value || undefined, to)}
      />
      <TextInput
        type="date"
        label={t('filter.to')}
        value={to ?? ''}
        onChange={(e) => onChange(from, e.currentTarget.value || undefined)}
      />
    </Group>
  );
}
