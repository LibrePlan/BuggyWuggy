// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Autocomplete, Group, Loader, Text } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useMemo, useState } from 'react';
import { useIssueSearch } from '../api/queries';
import type { IssueSummaryResponse } from '../api/types';
import { IssueTypeIcon } from './IssueIcons';

/**
 * A text field for an issue key, with suggestions from the issue search (by key or words).
 * The value is the typed text; choosing a suggestion fills in its key.
 */
export function IssuePicker({
  label,
  value,
  onChange,
  exclude,
  error,
  placeholder,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  exclude?: string;
  error?: React.ReactNode;
  placeholder?: string;
}) {
  const [debounced] = useDebouncedValue(value.trim(), 250);
  const [focused, setFocused] = useState(false);
  const results = useIssueSearch({ text: debounced }, 0, 10, focused && debounced.length > 0);

  const byKey = useMemo(() => {
    const map = new Map<string, IssueSummaryResponse>();
    for (const issue of results.data?.items ?? []) if (issue.key !== exclude) map.set(issue.key, issue);
    return map;
  }, [results.data, exclude]);

  return (
    <Autocomplete
      label={label}
      placeholder={placeholder}
      value={value}
      onChange={onChange}
      onFocus={() => setFocused(true)}
      data={[...byKey.keys()]}
      filter={({ options }) => options}
      error={error}
      data-autofocus
      rightSection={results.isFetching ? <Loader size="xs" /> : undefined}
      comboboxProps={{ withinPortal: true }}
      renderOption={({ option }) => {
        const issue = byKey.get(option.value);
        return (
          <Group gap={8} wrap="nowrap">
            {issue && <IssueTypeIcon type={issue.issueType} size={16} />}
            <Text size="sm" fw={500} style={{ whiteSpace: 'nowrap' }}>
              {option.value}
            </Text>
            <Text size="sm" c="dimmed" truncate>
              {issue?.summary}
            </Text>
          </Group>
        );
      }}
    />
  );
}
