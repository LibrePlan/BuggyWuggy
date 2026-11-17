// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import {
  Alert,
  Anchor,
  Box,
  Button,
  Container,
  Divider,
  Flex,
  Paper,
  PasswordInput,
  Stack,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { useDisclosure } from '@mantine/hooks';
import { useCallback, useEffect, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { IconLogin2 } from '@tabler/icons-react';
import { useTranslation } from 'react-i18next';
import { Link, Navigate, useLocation, useNavigate, useSearchParams } from 'react-router';
import { post } from '../api/client';
import { keys, useDonation, useMyself, useSetupStatus, useSso, useVersion } from '../api/queries';
import type { LoginResponse, MyselfResponse } from '../api/types';
import { DemoNotice } from '../components/DemoNotice';
import { Logo } from '../components/Logo';
import { PageLoader } from '../components/QueryState';
import { SupportProject } from '../components/SupportProject';
import { TwoFactorCode, TwoFactorEnrollment } from './TwoFactorSteps';

const DONATION_PAGE = '/admin/donation';

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
  const version = useVersion().data;
  const sso = useSso().data;
  const ssoError = params.get('sso-error');
  // With password login for system administrators only, the form is behind a link.
  const [adminLogin, { toggle: toggleAdminLogin }] = useDisclosure(false);
  // In demo mode the form is always there: the demo account logs in with its password.
  const passwordForm =
    !!version?.demo || !sso || sso.passwordLogin === 'ALL' || (sso.passwordLogin === 'SYSTEM_ADMINS' && adminLogin);
  const next = safeNext(params.get('next'));
  const setupDone = (location.state as { setupDone?: boolean } | null)?.setupDone;

  const form = useForm({
    initialValues: { username: '', password: '' },
    validate: {
      username: (v) => (v.trim() ? null : t('validation.required')),
      password: (v) => (v ? null : t('validation.required')),
    },
  });

  // In demo mode the demo account is filled in.
  const demo = version?.demo;
  const { setValues: setLoginValues } = form;
  useEffect(() => {
    if (demo) setLoginValues((v) => (v.username || v.password ? v : { username: demo.username, password: demo.password }));
  }, [demo, setLoginValues]);

  // With two-factor login, a correct password leads to a second step.
  const [step, setStep] = useState<LoginResponse['step']>('DONE');
  const [restartError, setRestartError] = useState<Error>();

  const loggedIn = (user: MyselfResponse) => {
    queryClient.clear();
    queryClient.setQueryData(keys.setup, { setupRequired: false });
    queryClient.setQueryData(keys.myself, user);
    navigate(next, { replace: true });
  };
  const restart = useCallback((error?: Error) => {
    setRestartError(error);
    setStep('DONE');
  }, []);

  const login = useMutation({
    mutationFn: (values: { username: string; password: string }) => post<LoginResponse>('/api/v1/auth/login', values),
    onMutate: () => setRestartError(undefined),
    onSuccess: (response) => {
      if (response.step === 'DONE' && response.user) loggedIn(response.user);
      else {
        form.setFieldValue('password', '');
        setStep(response.step);
      }
    },
  });

  if (setup.isPending || myself.isPending) return <PageLoader />;
  if (setup.data?.setupRequired) return <Navigate to="/setup" replace />;
  if (myself.data) return <Navigate to={next} replace />;

  return (
    <Container size={760} py={{ base: 40, sm: 80 }}>
      {/* The logo left of the login fields; on a narrow screen above them. */}
      <Flex direction={{ base: 'column', sm: 'row' }} align="center" justify="center" gap={{ base: 'lg', sm: 56 }}>
        <Box visibleFrom="sm">
          <Logo width={240} />
        </Box>
        <Box hiddenFrom="sm">
          <Logo width={150} />
        </Box>
        <Box w="100%" maw={420}>
          <Title order={2} ta="center">
            {t('login.title')}
          </Title>
          <Paper withBorder shadow="sm" p="lg" mt="lg" radius="md">
            {step === 'TWO_FACTOR' ? (
              <TwoFactorCode onDone={loggedIn} onRestart={restart} />
            ) : step === 'TWO_FACTOR_ENROLLMENT' ? (
              <TwoFactorEnrollment onDone={loggedIn} onRestart={restart} />
            ) : (
              <Stack>
                {ssoError && <Alert color="red">{t([`login.ssoError.${ssoError}`, 'login.ssoError.sso.failed'])}</Alert>}
                {sso?.providers.map((p) => (
                  <Button
                    key={p.id}
                    component="a"
                    href={`/oauth2/authorization/${encodeURIComponent(p.id)}?next=${encodeURIComponent(next)}`}
                    variant={sso.passwordLogin === 'ALL' ? 'default' : 'filled'}
                    leftSection={<IconLogin2 size={16} />}
                  >
                    {t('login.withProvider', { name: p.name })}
                  </Button>
                ))}
                {sso?.passwordLogin === 'SYSTEM_ADMINS' && (
                  <Anchor component="button" type="button" size="sm" ta="center" onClick={toggleAdminLogin}>
                    {t('login.adminLogin')}
                  </Anchor>
                )}
                {sso && sso.providers.length > 0 && sso.passwordLogin === 'ALL' && (
                  <Divider label={t('login.orPassword')} labelPosition="center" />
                )}
                {passwordForm && (
                  <form onSubmit={form.onSubmit((values) => login.mutate(values))}>
                    <Stack>
                      {setupDone && <Alert color="green">{t('setup.done')}</Alert>}
                      {login.isError && <Alert color="red">{login.error.message}</Alert>}
                      {restartError && <Alert color="red">{restartError.message}</Alert>}
                      <TextInput
                        label={t('user.username')}
                        required
                        autoFocus={!sso || sso.providers.length === 0}
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
                )}
              </Stack>
            )}
          </Paper>
        </Box>
      </Flex>
      {/* As wide as the logo and the login fields together. */}
      <Box maw={716} mx="auto">
        <DemoNotice />
        {donation.data && (
          <SupportProject
            status={donation.data.status}
            requestLink={
              <Anchor component={Link} to={`/login?next=${encodeURIComponent(DONATION_PAGE)}`} replace size="sm" ta="center">
                {t('donate.request')}
              </Anchor>
            }
            hint={next === DONATION_PAGE ? t('donate.loginAsAdmin') : undefined}
            defaultOpen={next === DONATION_PAGE}
            wide
          />
        )}
        {version && (
          <Text size="xs" c="dimmed" ta="center" mt="xl">
            {version.commit
              ? t('login.versionCommit', { version: version.version, commit: version.commit })
              : t('login.version', { version: version.version })}
          </Text>
        )}
      </Box>
    </Container>
  );
}
