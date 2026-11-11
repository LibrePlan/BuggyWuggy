// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { TagsInput } from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { useState } from 'react';
import { useLabelSuggestions } from '../api/queries';

export function LabelFilterInput({
  label,
  value,
  onChange,
}: {
  label: string;
  value: string[];
  onChange: (value: string[]) => void;
}) {
  const [search, setSearch] = useState('');
  const [opened, setOpened] = useState(false);
  const [debounced] = useDebouncedValue(search, 250);
  const suggestions = useLabelSuggestions(debounced, opened);
  return (
    <TagsInput
      label={label}
      data={suggestions.data ?? []}
      value={value}
      onChange={(labels) => onChange(labels.filter((l) => /^\S+$/.test(l)))}
      searchValue={search}
      onSearchChange={setSearch}
      onDropdownOpen={() => setOpened(true)}
      splitChars={[',', ' ']}
      clearable
    />
  );
}
