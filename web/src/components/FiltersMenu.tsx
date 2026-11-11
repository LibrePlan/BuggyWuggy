// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Button, Menu, Text } from '@mantine/core';
import { IconChevronDown, IconFilter, IconUsers, IconWorld } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { useFilters } from '../api/queries';
import type { FilterResponse } from '../api/types';

/** Lists the user's own and shared filters. */
export function FiltersMenu({ onSelect }: { onSelect: (filter: FilterResponse) => void }) {
  const { t } = useTranslation();
  const filters = useFilters().data ?? [];
  const mine = filters.filter((f) => f.mine);
  const shared = filters.filter((f) => !f.mine);

  return (
    <Menu position="bottom-start" width={300} withinPortal>
      <Menu.Target>
        <Button variant="default" leftSection={<IconFilter size={16} />} rightSection={<IconChevronDown size={14} />}>
          {t('filter.filters')}
        </Button>
      </Menu.Target>
      <Menu.Dropdown mah={420} style={{ overflowY: 'auto' }}>
        <Menu.Label>{t('filter.myFilters')}</Menu.Label>
        {mine.length === 0 && (
          <Text size="sm" c="dimmed" px="sm" pb="xs">
            {t('filter.noFilters')}
          </Text>
        )}
        {mine.map((f) => (
          <Menu.Item
            key={f.id}
            onClick={() => onSelect(f)}
            rightSection={f.sharedWith === 'PRIVATE' ? null : <ShareIcon filter={f} />}
          >
            {f.name}
          </Menu.Item>
        ))}
        {shared.length > 0 && (
          <>
            <Menu.Divider />
            <Menu.Label>{t('filter.sharedFilters')}</Menu.Label>
            {shared.map((f) => (
              <Menu.Item key={f.id} onClick={() => onSelect(f)}>
                <Text size="sm">{f.name}</Text>
                <Text size="xs" c="dimmed">
                  {f.owner.displayName}
                  {f.project && ` · ${f.project.name}`}
                </Text>
              </Menu.Item>
            ))}
          </>
        )}
      </Menu.Dropdown>
    </Menu>
  );
}

function ShareIcon({ filter }: { filter: FilterResponse }) {
  const { t } = useTranslation();
  const label = t(`filter.share.${filter.sharedWith}`);
  return filter.sharedWith === 'LOGGED_IN' ? (
    <IconWorld size={14} aria-label={label} />
  ) : (
    <IconUsers size={14} aria-label={label} />
  );
}
