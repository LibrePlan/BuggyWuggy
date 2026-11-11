// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Button, Container, Stack, Text, Title } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { useRouteError } from 'react-router';

/** Last-resort error screen for errors thrown while rendering a route. */
export function RouteError() {
  const { t } = useTranslation();
  const error = useRouteError();
  console.error(error);
  return (
    <Container size="sm" py="xl">
      <Stack>
        <Title order={2}>{t('error.title')}</Title>
        <Text>{error instanceof Error ? error.message : t('error.unknown')}</Text>
        <Button w="fit-content" onClick={() => window.location.assign('/')}>
          {t('error.home')}
        </Button>
      </Stack>
    </Container>
  );
}
