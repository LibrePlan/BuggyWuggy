// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Alert, Anchor, Box, Button, Collapse, Container, Divider, Image, Paper, PasswordInput, Stack, Text, TextInput, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useDisclosure } from '@mantine/hooks';
import { useCallback, useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { IconHeart, IconLogin2 } from '@tabler/icons-react';
import { Trans, useTranslation } from 'react-i18next';
import { Link, Navigate, useLocation, useNavigate, useSearchParams } from 'react-router';
import { post } from '../api/client';
import { keys, useDonation, useMyself, useSetupStatus, useSso, useVersion } from '../api/queries';
import type { LoginResponse, MyselfResponse, SupportStatus } from '../api/types';
import { PageLoader } from '../components/QueryState';
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
  const passwordForm = !sso || sso.passwordLogin === 'ALL' || (sso.passwordLogin === 'SYSTEM_ADMINS' && adminLogin);
  const next = safeNext(params.get('next'));
  const setupDone = (location.state as { setupDone?: boolean } | null)?.setupDone;
  // Open when the visitor came for the donation page.
  const [moreInfo, { toggle: toggleMoreInfo }] = useDisclosure(next === DONATION_PAGE);

  const form = useForm({
    initialValues: { username: '', password: '' },
    validate: {
      username: (v) => (v.trim() ? null : t('validation.required')),
      password: (v) => (v ? null : t('validation.required')),
    },
  });

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
    <Container size={420} py={80}>
      <Title order={2} ta="center">
        {t('login.title')}
      </Title>
      <Text c="dimmed" size="sm" ta="center" mt={5}>
        {t('app.name')}
      </Text>
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
      {donation.data && (
        <Stack gap="sm" mt="xl">
          <Title order={2} ta="center">
            {t('donate.title')}
          </Title>
          <Text size="sm" ta="center">
            {t('donate.intro')}
          </Text>
          <Anchor component={Link} to={`/login?next=${encodeURIComponent(DONATION_PAGE)}`} replace size="sm" ta="center">
            {t('donate.request')}
          </Anchor>
          {next === DONATION_PAGE && (
            <Text size="sm" c="dimmed" ta="center">
              {t('donate.loginAsAdmin')}
            </Text>
          )}
          <SupportStatusLine status={donation.data.status} />
          <Anchor component="button" type="button" onClick={toggleMoreInfo} aria-expanded={moreInfo} ta="center">
            {moreInfo ? t('donate.lessInfo') : t('donate.moreInfo')}
          </Anchor>
          <Collapse expanded={moreInfo}>
            <Paper withBorder p="md" radius="md">
              <Stack gap="sm">
                <Text size="sm">{t('donate.freedom')}</Text>
                <Text size="sm">{t('donate.people')}</Text>
                <Text size="sm">{t('donate.continuity')}</Text>
                <XkcdDependency />
              </Stack>
            </Paper>
          </Collapse>
        </Stack>
      )}
      {version && (
        <Text size="xs" c="dimmed" ta="center" mt="xl">
          {version.commit
            ? t('login.versionCommit', { version: version.version, commit: version.commit })
            : t('login.version', { version: version.version })}
        </Text>
      )}
    </Container>
  );
}

const SUPPORT_COLORS: Record<SupportStatus, string> = { NONE: 'blue', ACTIVE: 'green', EXPIRED: 'orange' };

/** Whether this installation supports the project: never, with a valid donation code, or no longer. */
function SupportStatusLine({ status }: { status: SupportStatus }) {
  const { t } = useTranslation();
  const key = status.toLowerCase() as Lowercase<SupportStatus>;
  return (
    <Alert color={SUPPORT_COLORS[status]} variant="light" icon={<IconHeart />} py="xs">
      <Text size="sm">
        <Text span inherit fw={700}>
          {t(`donate.status.${key}.title`)}
        </Text>
        {' — '}
        {t(`donate.status.${key}.text`)}
      </Text>
    </Alert>
  );
}

const XKCD_COMIC = 'https://xkcd.com/2347/';

/**
 * xkcd 2347 "Dependency" by Randall Munroe, CC BY-NC 2.5. Not part of this repository (its
 * NonCommercial license does not fit the AGPL of BuggyWuggy): the server downloads it once
 * from imgs.xkcd.com and serves it itself, so visitors never contact xkcd.com. Until it has
 * been downloaded (or when downloading is switched off), only the attribution is shown.
 */
function XkcdDependency() {
  const { t } = useTranslation();
  const [missing, setMissing] = useState(false);
  return (
    <Box component="figure" m={0}>
      {!missing && (
        <Anchor href={XKCD_COMIC} target="_blank" rel="noopener noreferrer" display="block">
          <Image
            src="/api/v1/donation/comic.png"
            srcSet="/api/v1/donation/comic-2x.png 2x"
            alt={t('donate.comicAlt')}
            title={t('donate.comicAlt')}
            w="100%"
            maw={385}
            mx="auto"
            loading="lazy"
            onError={() => setMissing(true)}
          />
        </Anchor>
      )}
      <Text component="figcaption" size="xs" c="dimmed" ta="center" mt={4}>
        <Trans
          i18nKey="donate.comicAttribution"
          components={{
            work: <Anchor href={XKCD_COMIC} target="_blank" rel="noopener noreferrer" inherit />,
            license: (
              <Anchor
                href="https://creativecommons.org/licenses/by-nc/2.5/"
                target="_blank"
                rel="license noopener noreferrer"
                inherit
              />
            ),
          }}
        />
      </Text>
    </Box>
  );
}
