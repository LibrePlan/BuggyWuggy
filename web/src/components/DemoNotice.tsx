// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Alert, Anchor, Text } from '@mantine/core';
import { IconPlayerPlay } from '@tabler/icons-react';
import { Trans, useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useVersion } from '../api/queries';

/** True when the user is the shared demo account of a demo build. */
export function useIsDemoAccount(username: string | undefined): boolean {
  const demo = useVersion().data?.demo;
  return !!demo && !!username && username.toLowerCase() === demo.username.toLowerCase();
}

/**
 * The rules of demo mode, on the login page (in full) and on every page of the application
 * (in short). Nothing is shown in a normal build.
 */
export function DemoNotice({ compact = false }: { compact?: boolean }) {
  const { t } = useTranslation();
  const demo = useVersion().data?.demo;
  if (!demo) return null;
  const values = { username: demo.username, password: demo.password, project: demo.projectKey };
  if (compact) {
    return (
      <Alert color="grape" variant="light" icon={<IconPlayerPlay size={16} />} py={6} mb="md">
        <Text size="sm">
          <Trans
            i18nKey="demo.short"
            values={values}
            components={{
              b: <Text span inherit fw={700} />,
              project: <Anchor component={Link} to={`/projects/${demo.projectKey}`} inherit fw={700} />,
            }}
          />
        </Text>
      </Alert>
    );
  }
  return (
    <Alert color="grape" variant="light" icon={<IconPlayerPlay size={18} />} title={t('demo.title')} mt="lg">
      <Text size="sm">
        <Trans i18nKey="demo.rules" values={values} components={{ b: <Text span inherit fw={700} /> }} />
      </Text>
    </Alert>
  );
}
