// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Loader, Select, type SelectProps } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import type { UseQueryResult } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useGroupSearch, useUserSearch } from '../api/queries';
import type { GroupRef, UserRef } from '../api/types';

type BaseProps = Omit<SelectProps, 'data' | 'value' | 'onChange' | 'searchValue' | 'onSearchChange'>;

interface Props<T> extends BaseProps {
  /** The selected item; {@code null} for none. Leave out for an "add" picker that resets after each choice. */
  value?: T | null;
  onChange: (item: T | null) => void;
  /** Ids not to offer, e.g. members that are already added. */
  exclude?: number[];
}

function SearchSelect<T extends { id: number }>({
  search: useSearch,
  labelOf,
  value,
  onChange,
  exclude = [],
  ...props
}: Props<T> & {
  search: (query: string, enabled: boolean) => UseQueryResult<T[]>;
  labelOf: (item: T) => string;
}) {
  const { t } = useTranslation();
  const [text, setText] = useState('');
  const [opened, setOpened] = useState(false);
  const [debounced] = useDebouncedValue(text, 250);
  const query = value && debounced === labelOf(value) ? '' : debounced;
  const results = useSearch(query, opened);
  const resetting = value === undefined;
  // An "add" picker starts afresh after each choice; remounting clears Mantine's own search text.
  const [generation, setGeneration] = useState(0);

  const byId = useMemo(() => {
    const map = new Map<string, T>();
    if (value) map.set(String(value.id), value);
    for (const item of results.data ?? []) if (!exclude.includes(item.id)) map.set(String(item.id), item);
    return map;
  }, [results.data, value, exclude]);

  return (
    <Select
      key={generation}
      searchable
      clearable={!resetting && !props.required}
      data={[...byId.values()].map((item) => ({ value: String(item.id), label: labelOf(item) }))}
      value={value ? String(value.id) : null}
      onChange={(id) => {
        onChange(id ? (byId.get(id) ?? null) : null);
        if (resetting) {
          setText('');
          setOpened(false);
          setGeneration((g) => g + 1);
        }
      }}
      searchValue={text}
      onSearchChange={setText}
      onDropdownOpen={() => setOpened(true)}
      filter={({ options }) => options}
      nothingFoundMessage={results.isFetching ? undefined : t('common.nothingFound')}
      rightSection={results.isFetching ? <Loader size="xs" /> : undefined}
      comboboxProps={{ withinPortal: true }}
      {...props}
    />
  );
}

/** Picks any active user; searches on the server (needs BROWSE_USERS). */
export function AnyUserPicker(props: Props<UserRef>) {
  return <SearchSelect search={useUserSearch} labelOf={(u) => `${u.displayName} (${u.username})`} {...props} />;
}

/** Picks a group; searches on the server (needs BROWSE_USERS). */
export function GroupPicker(props: Props<GroupRef>) {
  return <SearchSelect search={useGroupSearch} labelOf={(g) => g.name} {...props} />;
}
