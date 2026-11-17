// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Anchor, AppShell, Avatar, Group, Menu, Text, TextInput, UnstyledButton } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import {
  IconBook,
  IconChevronDown,
  IconInfoCircle,
  IconLicense,
  IconLogout,
  IconSearch,
  IconSettings,
  IconTool,
  IconUser,
} from '@tabler/icons-react';
import { useState, type ReactNode } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link, NavLink, Outlet, useNavigate } from 'react-router';
import { post } from '../api/client';
import { keys, useMyself } from '../api/queries';
import { normalizeIssueKey } from '../lib/links';
import { DemoNotice } from './DemoNotice';
import { initials } from './UserLabel';

export function AppLayout() {
  const { t } = useTranslation();
  const myself = useMyself().data;
  const queryClient = useQueryClient();
  const navigate = useNavigate();

  async function logout() {
    let response: { redirectUrl?: string } | undefined;
    try {
      response = await post<{ redirectUrl?: string } | undefined>('/api/v1/auth/logout');
    } catch (e) {
      notifications.show({ color: 'red', message: e instanceof Error ? e.message : String(e) });
      return;
    }
    queryClient.clear();
    queryClient.setQueryData(keys.myself, null);
    // After single sign-on, log out at the identity provider too; it sends the browser back.
    if (response?.redirectUrl) window.location.assign(response.redirectUrl);
    else navigate('/login', { replace: true });
  }

  return (
    <AppShell header={{ height: 56 }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          <Group gap="lg" wrap="nowrap">
            <Anchor component={Link} to="/" underline="never" c="inherit">
              <Group gap={8} wrap="nowrap">
                <img src="/favicon.svg" alt="" width={26} height={26} />
                <Text fw={700} size="lg" visibleFrom="sm">
                  {t('app.name')}
                </Text>
              </Group>
            </Anchor>
            <Anchor component={NavLink} to="/projects" c="inherit" fw={500}>
              {t('nav.projects')}
            </Anchor>
            <Anchor component={NavLink} to="/issues?resolution=unresolved&assignee=me" c="inherit" fw={500}>
              {t('nav.issues')}
            </Anchor>
            {myself?.globalPermissions.includes('ADMINISTER') && (
              <Anchor component={NavLink} to="/admin" c="inherit" fw={500} visibleFrom="sm">
                {t('nav.admin')}
              </Anchor>
            )}
            <HelpMenu admin={!!myself?.globalPermissions.includes('ADMINISTER')} />
          </Group>
          <Group gap="sm" wrap="nowrap">
            <QuickSearch />
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
                  <Menu.Item leftSection={<IconUser size={16} />} component={Link} to="/profile">
                    {t('nav.profile')}
                  </Menu.Item>
                  {myself.globalPermissions.includes('ADMINISTER') && (
                    <Menu.Item leftSection={<IconSettings size={16} />} component={Link} to="/admin">
                      {t('nav.admin')}
                    </Menu.Item>
                  )}
                  <Menu.Divider />
                  <Menu.Item leftSection={<IconLogout size={16} />} onClick={() => void logout()}>
                    {t('nav.logout')}
                  </Menu.Item>
                </Menu.Dropdown>
              </Menu>
            )}
          </Group>
        </Group>
      </AppShell.Header>
      <AppShell.Main>
        <DemoNotice compact />
        <Outlet />
      </AppShell.Main>
    </AppShell>
  );
}

/** Jumps to an issue when a key is typed, otherwise searches for the text. */
function QuickSearch() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [value, setValue] = useState('');
  return (
    <TextInput
      size="xs"
      w={{ base: 140, sm: 220 }}
      placeholder={t('nav.quickSearch')}
      aria-label={t('nav.quickSearch')}
      leftSection={<IconSearch size={14} />}
      value={value}
      onChange={(e) => setValue(e.currentTarget.value)}
      onKeyDown={(e) => {
        if (e.key !== 'Enter' || !value.trim()) return;
        const key = normalizeIssueKey(value);
        navigate(key && !/^\d+$/.test(key) ? `/browse/${key}` : `/issues?q=${encodeURIComponent(value.trim())}`);
        setValue('');
        e.currentTarget.blur();
      }}
    />
  );
}

/** Help: the guides, the license and About, each in a tab of its own. */
function HelpMenu({ admin }: { admin: boolean }) {
  const { t } = useTranslation();
  const item = (href: string, icon: ReactNode, label: string) => (
    <Menu.Item component="a" href={href} target="_blank" rel="noopener" leftSection={icon}>
      {label}
    </Menu.Item>
  );
  return (
    <Menu position="bottom-start" withinPortal>
      <Menu.Target>
        <UnstyledButton fw={500}>
          <Group gap={4} wrap="nowrap">
            {t('nav.help')}
            <IconChevronDown size={14} />
          </Group>
        </UnstyledButton>
      </Menu.Target>
      <Menu.Dropdown>
        {item('/help/user-guide', <IconBook size={16} />, t('help.userGuide'))}
        {admin && item('/help/admin-guide', <IconTool size={16} />, t('help.adminGuide'))}
        {item('/help/license', <IconLicense size={16} />, t('help.license'))}
        {item('/help/about', <IconInfoCircle size={16} />, t('help.about'))}
      </Menu.Dropdown>
    </Menu>
  );
}
