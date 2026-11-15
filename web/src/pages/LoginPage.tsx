// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Alert, Anchor, Box, Button, Collapse, Container, Image, Paper, PasswordInput, Stack, Text, TextInput, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useDisclosure } from '@mantine/hooks';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { IconHeart } from '@tabler/icons-react';
import { Trans, useTranslation } from 'react-i18next';
import { Link, Navigate, useLocation, useNavigate, useSearchParams } from 'react-router';
import { post } from '../api/client';
import { keys, useDonation, useMyself, useSetupStatus } from '../api/queries';
import type { MyselfResponse, SupportStatus } from '../api/types';
import { PageLoader } from '../components/QueryState';

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
 * xkcd 2347 "Dependency" by Randall Munroe, CC BY-NC 2.5. Embedded from imgs.xkcd.com as
 * xkcd allows, not copied into this repository: its NonCommercial license does not fit the
 * AGPL of BuggyWuggy. Loaded only when the donation text is opened, without a referrer.
 */
function XkcdDependency() {
  const { t } = useTranslation();
  return (
    <Box component="figure" m={0}>
      <Anchor href={XKCD_COMIC} target="_blank" rel="noopener noreferrer" display="block">
        <Image
          src="https://imgs.xkcd.com/comics/dependency.png"
          srcSet="https://imgs.xkcd.com/comics/dependency_2x.png 2x"
          alt={t('donate.comicAlt')}
          title={t('donate.comicAlt')}
          w="100%"
          maw={385}
          mx="auto"
          loading="lazy"
          referrerPolicy="no-referrer"
        />
      </Anchor>
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
