// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Alert, Anchor, Button, Container, Paper, PasswordInput, Stack, Text, TextInput, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link, Navigate, useLocation, useNavigate, useSearchParams } from 'react-router';
import { post } from '../api/client';
import { keys, useDonation, useMyself, useSetupStatus } from '../api/queries';
import type { MyselfResponse } from '../api/types';
import { PageLoader } from '../components/QueryState';

const LICENSE_PAGE = '/admin/license';

/** Only follow redirects within the app. */
function safeNext(next: string | null): string {
  return next && next.startsWith('/') && !next.startsWith('//') ? next : '/';
}

export function LoginPage() {
  const { t } = useTranslation();
  const [params] = useSearchParams();
  const location = useLocation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const setup = useSetupStatus();
  const myself = useMyself();
  const donation = useDonation();
  const next = safeNext(params.get('next'));
  const setupDone = (location.state as { setupDone?: boolean } | null)?.setupDone;

  const form = useForm({
    initialValues: { username: '', password: '' },
    validate: {
      username: (v) => (v.trim() ? null : t('validation.required')),
      password: (v) => (v ? null : t('validation.required')),
    },
  });

  const login = useMutation({
    mutationFn: (values: { username: string; password: string }) => post<MyselfResponse>('/api/v1/auth/login', values),
    onSuccess: (user) => {
      queryClient.clear();
      queryClient.setQueryData(keys.setup, { setupRequired: false });
      queryClient.setQueryData(keys.myself, user);
      navigate(next, { replace: true });
    },
  });

  if (setup.isPending || myself.isPending) return <PageLoader />;
  if (setup.data?.setupRequired) return <Navigate to="/setup" replace />;
  if (myself.data) return <Navigate to={next} replace />;

  return (
    <Container size={420} py={80}>
      <Title order={2} ta="center">
        {t('login.title')}
      </Title>
      <Text c="dimmed" size="sm" ta="center" mt={5}>
        {t('app.name')}
      </Text>
      <Paper withBorder shadow="sm" p="lg" mt="lg" radius="md">
        <form onSubmit={form.onSubmit((values) => login.mutate(values))}>
          <Stack>
            {setupDone && <Alert color="green">{t('setup.done')}</Alert>}
            {login.isError && <Alert color="red">{login.error.message}</Alert>}
            <TextInput
              label={t('user.username')}
              required
              autoFocus
              autoComplete="username"
              {...form.getInputProps('username')}
            />
            <PasswordInput
              label={t('user.password')}
              required
              autoComplete="current-password"
              {...form.getInputProps('password')}
            />
            <Button type="submit" loading={login.isPending}>
              {t('login.submit')}
            </Button>
          </Stack>
        </form>
      </Paper>
      {donation.data?.show && (
        <Stack gap={4} mt="xl" align="center" ta="center">
          <Title order={1}>{t('donate.title')}</Title>
          <Text>
            {t('donate.notFree')}{' '}
            <Anchor component={Link} to={`/login?next=${encodeURIComponent(LICENSE_PAGE)}`} replace>
              {t('donate.findOut')}
            </Anchor>
          </Text>
          {next === LICENSE_PAGE && (
            <Text size="sm" c="dimmed">
              {t('donate.loginAsAdmin')}
            </Text>
          )}
        </Stack>
      )}
    </Container>
  );
}
