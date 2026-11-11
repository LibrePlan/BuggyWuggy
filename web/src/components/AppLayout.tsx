// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Anchor, AppShell, Avatar, Group, Menu, Text, UnstyledButton } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconChevronDown, IconLogout } from '@tabler/icons-react';
import { useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link, NavLink, Outlet, useNavigate } from 'react-router';
import { post } from '../api/client';
import { keys, useMyself } from '../api/queries';
import { initials } from './UserLabel';

export function AppLayout() {
  const { t } = useTranslation();
  const myself = useMyself().data;
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  async function logout() {
    try {
      await post('/api/v1/auth/logout');
    } catch (e) {
      notifications.show({ color: 'red', message: e instanceof Error ? e.message : String(e) });
      return;
    }
    queryClient.clear();
    queryClient.setQueryData(keys.myself, null);
    navigate('/login', { replace: true });
  }

  return (
    <AppShell header={{ height: 56 }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          <Group gap="lg" wrap="nowrap">
            <Anchor component={Link} to="/" underline="never" c="inherit">
              <Group gap={8} wrap="nowrap">
                <img src="/favicon.svg" alt="" width={26} height={26} />
                <Text fw={700} size="lg">
                  {t('app.name')}
                </Text>
              </Group>
            </Anchor>
            <Anchor component={NavLink} to="/projects" c="inherit" fw={500}>
              {t('nav.projects')}
            </Anchor>
          </Group>
          {myself && (
            <Menu position="bottom-end" withinPortal>
              <Menu.Target>
                <UnstyledButton aria-label={t('nav.userMenu')}>
                  <Group gap={6} wrap="nowrap">
                    <Avatar size="sm" radius="xl" color="blue">
                      {initials(myself.displayName)}
                    </Avatar>
                    <Text size="sm" visibleFrom="xs">
                      {myself.displayName}
                    </Text>
                    <IconChevronDown size={14} />
                  </Group>
                </UnstyledButton>
              </Menu.Target>
              <Menu.Dropdown>
                <Menu.Label>{myself.username}</Menu.Label>
                <Menu.Item leftSection={<IconLogout size={16} />} onClick={() => void logout()}>
                  {t('nav.logout')}
                </Menu.Item>
              </Menu.Dropdown>
            </Menu>
          )}
        </Group>
      </AppShell.Header>
      <AppShell.Main>
        <Outlet />
      </AppShell.Main>
    </AppShell>
  );
}
