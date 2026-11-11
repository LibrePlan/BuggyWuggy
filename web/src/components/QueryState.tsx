// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Alert, Center, Loader, type AlertProps } from '@mantine/core';
import { IconAlertTriangle } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '../lib/apiError';

/** An error message for a failed request. */
export function ErrorAlert({ error, ...props }: { error: unknown } & Omit<AlertProps, 'children'>) {
  const { t } = useTranslation();
  const message = error instanceof Error ? error.message : t('error.unknown');
  return (
    <Alert color="red" icon={<IconAlertTriangle />} title={t('error.title')} {...props}>
      {message}
    </Alert>
  );
}

export function PageLoader() {
  return (
    <Center py="xl">
      <Loader />
    </Center>
  );
}

export const isNotFound = (error: unknown) => error instanceof ApiError && error.status === 404;
