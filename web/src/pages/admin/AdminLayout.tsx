// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Stack, Tabs, Title } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { Outlet, useLocation, useNavigate } from 'react-router';
import { useMyself } from '../../api/queries';
import { NotFoundPage } from '../NotFoundPage';
import { ImportStatusLine } from './ImportPage';

const SECTIONS = ['users', 'groups', 'permissions', 'email', 'import'] as const;

/** Frame of the administration pages; only for users with the ADMINISTER permission. */
export function AdminLayout() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const myself = useMyself().data;
  if (!myself?.globalPermissions.includes('ADMINISTER')) return <NotFoundPage />;
  const current = SECTIONS.find((s) => location.pathname.startsWith(`/admin/${s}`)) ?? 'users';

  return (
    <Stack>
      <Title order={2}>{t('admin.title')}</Title>
      <Tabs value={current} onChange={(v) => v && navigate(`/admin/${v}`)}>
        <Tabs.List>
          {SECTIONS.map((s) => (
            <Tabs.Tab key={s} value={s}>
              {t(`admin.${s}`)}
            </Tabs.Tab>
          ))}
        </Tabs.List>
      </Tabs>
      <ImportStatusLine />
      <Outlet />
    </Stack>
  );
}
