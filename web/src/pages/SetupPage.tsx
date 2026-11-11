// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Alert, Button, Container, Paper, PasswordInput, Stack, Text, TextInput, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Navigate, useNavigate } from 'react-router';
import { post } from '../api/client';
import { keys, useSetupStatus } from '../api/queries';
import type { SetupRequest } from '../api/types';
import { PageLoader } from '../components/QueryState';
import { fieldErrorMap } from '../lib/apiError';

/** First-time setup: creates the first administrator, using the setup token from the server log. */
export function SetupPage() {
  const { t } = useTranslation();
  const status = useSetupStatus();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const form = useForm<SetupRequest & { confirm: string }>({
    initialValues: { setupToken: '', username: 'admin', displayName: '', email: '', password: '', confirm: '' },
    validate: {
      setupToken: (v) => (v.trim() ? null : t('validation.required')),
      username: (v) => (v.trim() ? null : t('validation.required')),
      displayName: (v) => (v.trim() ? null : t('validation.required')),
      email: (v) => (/^\S+@\S+$/.test(v) ? null : t('validation.email')),
      password: (v) => (v.length >= 12 ? null : t('validation.passwordLength', { min: 12 })),
      confirm: (v, values) => (v === values.password ? null : t('validation.passwordMismatch')),
    },
  });

  const setup = useMutation({
    mutationFn: ({ confirm: _, ...request }: SetupRequest & { confirm: string }) =>
      post('/api/v1/setup/admin', { ...request, setupToken: request.setupToken.trim() }),
    onSuccess: () => {
      queryClient.setQueryData(keys.setup, { setupRequired: false });
      navigate('/login', { replace: true, state: { setupDone: true } });
    },
    onError: (e) => form.setErrors(fieldErrorMap(e)),
  });

  if (status.isPending) return <PageLoader />;
  if (status.data && !status.data.setupRequired) return <Navigate to="/" replace />;

  return (
    <Container size={460} py={60}>
      <Title order={2} ta="center">
        {t('setup.title')}
      </Title>
      <Text c="dimmed" size="sm" ta="center" mt={5}>
        {t('setup.intro')}
      </Text>
      <Paper withBorder shadow="sm" p="lg" mt="lg" radius="md">
        <form onSubmit={form.onSubmit((values) => setup.mutate(values))}>
          <Stack>
            {setup.isError && Object.keys(fieldErrorMap(setup.error)).length === 0 && (
              <Alert color="red">{setup.error.message}</Alert>
            )}
            <TextInput
              label={t('setup.token')}
              description={t('setup.tokenHelp')}
              required
              autoComplete="off"
              {...form.getInputProps('setupToken')}
            />
            <TextInput label={t('user.username')} required autoComplete="username" {...form.getInputProps('username')} />
            <TextInput label={t('user.displayName')} required autoComplete="name" {...form.getInputProps('displayName')} />
            <TextInput label={t('user.email')} type="email" required autoComplete="email" {...form.getInputProps('email')} />
            <PasswordInput label={t('user.password')} required autoComplete="new-password" {...form.getInputProps('password')} />
            <PasswordInput
              label={t('user.confirmPassword')}
              required
              autoComplete="new-password"
              {...form.getInputProps('confirm')}
            />
            <Button type="submit" loading={setup.isPending}>
              {t('setup.submit')}
            </Button>
          </Stack>
        </form>
      </Paper>
    </Container>
  );
}
