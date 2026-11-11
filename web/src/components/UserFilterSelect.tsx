// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { MultiSelect } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useUserSearch, useUsersByIds } from '../api/queries';
import type { UserRef } from '../api/types';

/**
 * Chooses users for a filter: "Current user", "Unassigned"/"None" and named users. Values
 * are "me", "none" and user ids, as in the search API. Without the BROWSE_USERS permission
 * only the first two are offered.
 */
export function UserFilterSelect({
  label,
  noneLabel,
  value,
  onChange,
}: {
  label: string;
  noneLabel: string;
  value: string[];
  onChange: (value: string[]) => void;
}) {
  const { t } = useTranslation();
  const [search, setSearch] = useState('');
  const [opened, setOpened] = useState(false);
  const [debounced] = useDebouncedValue(search, 250);
  const found = useUserSearch(debounced, opened);
  const selectedIds = value.filter((v) => /^\d+$/.test(v)).map(Number);
  const selected = useUsersByIds(selectedIds);
  const [seen, setSeen] = useState(new Map<string, UserRef>());

  const users = useMemo(() => {
    const map = new Map(seen);
    for (const u of [...(selected.data ?? []), ...(found.data ?? [])]) map.set(String(u.id), u);
    return map;
  }, [seen, selected.data, found.data]);

  const data = [
    { value: 'me', label: t('filter.currentUser') },
    { value: 'none', label: noneLabel },
    ...[...users.values()].map((u) => ({ value: String(u.id), label: u.displayName })),
    // Ids that cannot be resolved (e.g. without permission) still need an option.
    ...selectedIds.filter((id) => !users.has(String(id))).map((id) => ({ value: String(id), label: `#${id}` })),
  ];

  return (
    <MultiSelect
      label={label}
      data={data}
      value={value}
      onChange={(next) => {
        setSeen(new Map([...seen, ...next.flatMap((v) => (users.has(v) ? [[v, users.get(v)!] as const] : []))]));
        onChange(next);
      }}
      searchable
      searchValue={search}
      onSearchChange={setSearch}
      onDropdownOpen={() => setOpened(true)}
      filter={({ options, search: s }) =>
        options.filter((o) => 'label' in o && o.label.toLowerCase().includes(s.toLowerCase().trim()))
      }
      clearable
      comboboxProps={{ withinPortal: true }}
    />
  );
}
