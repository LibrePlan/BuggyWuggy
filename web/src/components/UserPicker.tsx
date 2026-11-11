// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Loader, Select, type SelectProps } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAssignableUsers } from '../api/queries';
import type { UserRef } from '../api/types';

export const UNASSIGNED = '';

interface Props extends Omit<SelectProps, 'data' | 'value' | 'onChange' | 'searchValue' | 'onSearchChange'> {
  projectKey: string;
  /** The current user; shown even when not in the search results. */
  value: UserRef | null;
  onChange: (user: UserRef | null) => void;
  allowUnassigned?: boolean;
}

/** Picks a user who can be assigned issues in the project; searches on the server. */
export function UserPicker({ projectKey, value, onChange, allowUnassigned = true, ...props }: Props) {
  const { t } = useTranslation();
  const [search, setSearch] = useState('');
  const [opened, setOpened] = useState(false);
  const [debounced] = useDebouncedValue(search, 250);
  // Do not search for the text of the selected option itself.
  const query = value && debounced === value.displayName ? '' : debounced;
  const users = useAssignableUsers(projectKey, query, opened);

  const byId = useMemo(() => {
    const map = new Map<string, UserRef>();
    if (value) map.set(String(value.id), value);
    for (const u of users.data ?? []) map.set(String(u.id), u);
    return map;
  }, [users.data, value]);

  const data = [
    ...(allowUnassigned ? [{ value: UNASSIGNED, label: t('issue.unassigned') }] : []),
    ...[...byId.values()].map((u) => ({ value: String(u.id), label: u.displayName })),
  ];

  return (
    <Select
      searchable
      data={data}
      value={value ? String(value.id) : allowUnassigned ? UNASSIGNED : null}
      onChange={(id) => onChange(id ? (byId.get(id) ?? null) : null)}
      searchValue={search}
      onSearchChange={setSearch}
      onDropdownOpen={() => setOpened(true)}
      filter={({ options }) => options}
      nothingFoundMessage={users.isFetching ? undefined : t('common.nothingFound')}
      rightSection={users.isFetching ? <Loader size="xs" /> : undefined}
      allowDeselect={false}
      comboboxProps={{ withinPortal: true }}
      {...props}
    />
  );
}
