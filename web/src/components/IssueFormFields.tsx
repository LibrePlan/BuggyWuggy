// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Group, MultiSelect, Select, TagsInput, Text, TextInput } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { useComponents, usePriorities, useVersions } from '../api/queries';
import type { IssueTypeResponse, PriorityResponse } from '../api/types';
import { IssueTypeIcon, PriorityIcon } from './IssueIcons';

const LABEL_PATTERN = /^\S{1,255}$/;

export function IssueTypeSelect({
  types,
  value,
  onChange,
  error,
}: {
  types: IssueTypeResponse[];
  value: string | null;
  onChange: (value: string | null) => void;
  error?: React.ReactNode;
}) {
  const { t } = useTranslation();
  const byId = new Map(types.map((type) => [String(type.id), type]));
  return (
    <Select
      label={t('issue.type')}
      required
      allowDeselect={false}
      data={types.map((type) => ({ value: String(type.id), label: type.name }))}
      value={value}
      onChange={onChange}
      error={error}
      leftSection={value && byId.get(value) ? <IssueTypeIcon type={byId.get(value)!} size={16} /> : undefined}
      renderOption={({ option }) => (
        <Group gap={8} wrap="nowrap">
          <IssueTypeIcon type={byId.get(option.value)!} size={16} />
          <Text size="sm">{option.label}</Text>
        </Group>
      )}
    />
  );
}

export function PrioritySelect({
  value,
  onChange,
}: {
  value: string | null;
  onChange: (value: string | null) => void;
}) {
  const { t } = useTranslation();
  const priorities = usePriorities().data ?? [];
  const byId = new Map<string, PriorityResponse>(priorities.map((p) => [String(p.id), p]));
  return (
    <Select
      label={t('issue.priority')}
      data={priorities.map((p) => ({ value: String(p.id), label: p.name }))}
      value={value}
      onChange={onChange}
      allowDeselect={false}
      leftSection={value && byId.get(value) ? <PriorityIcon priority={byId.get(value)!} size={16} /> : undefined}
      renderOption={({ option }) => (
        <Group gap={8} wrap="nowrap">
          <PriorityIcon priority={byId.get(option.value)!} size={16} />
          <Text size="sm">{option.label}</Text>
        </Group>
      )}
    />
  );
}

export function LabelsInput({ value, onChange }: { value: string[]; onChange: (value: string[]) => void }) {
  const { t } = useTranslation();
  return (
    <TagsInput
      label={t('issue.labels')}
      description={t('issue.labelsHelp')}
      value={value}
      onChange={(labels) => onChange(labels.filter((l) => LABEL_PATTERN.test(l)))}
      splitChars={[',', ' ']}
      clearable
    />
  );
}

export function ComponentsSelect({
  projectKey,
  value,
  onChange,
}: {
  projectKey: string;
  value: string[];
  onChange: (value: string[]) => void;
}) {
  const { t } = useTranslation();
  const components = useComponents(projectKey).data ?? [];
  if (components.length === 0 && value.length === 0) return null;
  return (
    <MultiSelect
      label={t('issue.components')}
      data={components.map((c) => ({ value: String(c.id), label: c.name }))}
      value={value}
      onChange={onChange}
      searchable
      clearable
    />
  );
}

export function VersionsSelect({
  projectKey,
  label,
  value,
  onChange,
}: {
  projectKey: string;
  label: string;
  value: string[];
  onChange: (value: string[]) => void;
}) {
  const versions = useVersions(projectKey).data ?? [];
  if (versions.length === 0 && value.length === 0) return null;
  return (
    <MultiSelect
      label={label}
      data={versions.map((v) => ({ value: String(v.id), label: v.name }))}
      value={value}
      onChange={onChange}
      searchable
      clearable
    />
  );
}

export function DueDateInput({ value, onChange }: { value: string; onChange: (value: string) => void }) {
  const { t } = useTranslation();
  return (
    <TextInput
      type="date"
      label={t('issue.dueDate')}
      value={value}
      onChange={(e) => onChange(e.currentTarget.value)}
      maw={220}
    />
  );
}

export const toIds = (values: string[]) => values.map(Number);
export const toValues = (items: { id: number }[]) => items.map((i) => String(i.id));
