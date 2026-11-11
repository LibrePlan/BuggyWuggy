// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Button, Stack, Text, Title } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

export function NotFoundPage({ message }: { message?: string }) {
  const { t } = useTranslation();
  return (
    <Stack align="center" py={60}>
      <Title order={2}>{t('notFound.title')}</Title>
      <Text c="dimmed">{message ?? t('notFound.text')}</Text>
      <Button component={Link} to="/projects" variant="light">
        {t('notFound.back')}
      </Button>
    </Stack>
  );
}
