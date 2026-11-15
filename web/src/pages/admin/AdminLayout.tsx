// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Alert, Anchor, Stack, Tabs, Text, Title } from '@mantine/core';
import { IconHeart } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Link, Outlet, useLocation, useNavigate } from 'react-router';
import { useDonation, useMyself } from '../../api/queries';
import { NotFoundPage } from '../NotFoundPage';
import { ImportStatusLine } from './ImportPage';

const SECTIONS = ['users', 'groups', 'permissions', 'board', 'email', 'import', 'license'] as const;

/** Frame of the administration pages; only for users with the ADMINISTER permission. */
export function AdminLayout() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const myself = useMyself().data;
  const donation = useDonation().data;
  if (!myself?.globalPermissions.includes('ADMINISTER')) return <NotFoundPage />;
  const current = SECTIONS.find((s) => location.pathname.startsWith(`/admin/${s}`)) ?? 'users';

  return (
    <Stack>
      <Title order={2}>{t('admin.title')}</Title>
      <Tabs value={current} onChange={(v) => v && navigate(`/admin/${v}`)}>
        <Tabs.List>
          {SECTIONS.map((s) => (
            <Tabs.Tab key={s} value={s}>
              {t(s === 'permissions' ? 'admin.permissions.title' : `admin.${s}`)}
            </Tabs.Tab>
          ))}
        </Tabs.List>
      </Tabs>
      {donation?.show && current !== 'license' && (
        <Alert color="blue" variant="light" icon={<IconHeart />} title={t('license.unlicensed')}>
          <Text size="sm">
            {t('license.unlicensedText')}{' '}
            <Anchor component={Link} to="/admin/license" size="sm">
              {t('license.requestQuotation')}
            </Anchor>
          </Text>
        </Alert>
      )}
      <ImportStatusLine />
      <Outlet />
    </Stack>
  );
}
