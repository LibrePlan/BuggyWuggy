// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import {
  Alert,
  Anchor,
  Box,
  Button,
  Checkbox,
  Code,
  CopyButton,
  Group,
  Image,
  Loader,
  SimpleGrid,
  Stack,
  Stepper,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { notifications } from '@mantine/notifications';
import { useMutation, useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { get, post } from '../api/client';
import type { EnrollmentResponse, EnrollmentResult, MyselfResponse, TwoFactorLoginResponse } from '../api/types';
import { ApiError } from '../lib/apiError';

/** Errors after which the login has to start again with the password. */
export function endsLogin(error: unknown): boolean {
  return (
    error instanceof ApiError &&
    (error.status === 429 || error.code === 'auth.two-factor.no-login' || error.code === 'auth.two-factor.expired')
  );
}

interface StepProps {
  /** The login is complete. */
  onDone: (user: MyselfResponse) => void;
  /** The half-finished login ended (e.g. timed out): back to the password, with this error. */
  onRestart: (error?: Error) => void;
}

function CodeForm({
  label,
  description,
  submit,
  loading,
  error,
  onSubmit,
}: {
  label: string;
  description?: string;
  submit: string;
  loading: boolean;
  error: Error | null;
  onSubmit: (code: string) => void;
}) {
  const { t } = useTranslation();
  const form = useForm({
    initialValues: { code: '' },
    validate: { code: (v) => (v.trim() ? null : t('validation.required')) },
  });
  return (
    <form onSubmit={form.onSubmit((v) => onSubmit(v.code.trim()))}>
      <Stack>
        {error && <Alert color="red">{error.message}</Alert>}
        <TextInput
          label={label}
          description={description}
          required
          autoFocus
          autoComplete="one-time-code"
          spellCheck={false}
          {...form.getInputProps('code')}
        />
        <Button type="submit" loading={loading}>
          {submit}
        </Button>
      </Stack>
    </form>
  );
}

/** The second step of a login: a code from the authenticator app, or a recovery code. */
export function TwoFactorCode({ onDone, onRestart }: StepProps) {
  const { t } = useTranslation();
  const verify = useMutation({
    mutationFn: (code: string) => post<TwoFactorLoginResponse>('/api/v1/auth/two-factor', { code }),
    onSuccess: (result) => {
      if (result.recoveryCodeUsed) {
        const left = result.recoveryCodesLeft;
        notifications.show({
          color: 'orange',
          autoClose: false,
          message: `${t('login.recoveryCodeUsed', { count: left })}${left <= 3 ? ` ${t('login.recoveryCodesLow')}` : ''}`,
        });
      }
      onDone(result.user);
    },
    onError: (e) => endsLogin(e) && onRestart(e),
  });
  return (
    <Stack>
      <Title order={4}>{t('login.codeTitle')}</Title>
      <Text size="sm">{t('login.codeIntro')}</Text>
      <CodeForm
        label={t('login.code')}
        description={t('login.codeHelp')}
        submit={t('login.verify')}
        loading={verify.isPending}
        error={verify.error}
        onSubmit={(code) => verify.mutate(code)}
      />
      <Anchor component="button" type="button" size="sm" onClick={() => onRestart()}>
        {t('login.restart')}
      </Anchor>
    </Stack>
  );
}

/** The wizard for a user without a second factor: scan the QR code, check a code, keep the recovery codes. */
export function TwoFactorEnrollment({ onDone, onRestart }: StepProps) {
  const { t } = useTranslation();
  const [active, setActive] = useState(0);
  const [result, setResult] = useState<EnrollmentResult>();
  const [saved, setSaved] = useState(false);

  const enrollment = useQuery({
    queryKey: ['two-factor-enrollment'],
    queryFn: () => get<EnrollmentResponse>('/api/v1/auth/two-factor/enrollment'),
    retry: false,
    staleTime: Infinity,
    gcTime: 0,
  });
  const confirm = useMutation({
    mutationFn: (code: string) => post<EnrollmentResult>('/api/v1/auth/two-factor/enrollment', { code }),
    onSuccess: (r) => {
      setResult(r);
      setActive(2);
    },
    onError: (e) => (endsLogin(e) || (e instanceof ApiError && e.status === 409)) && onRestart(e),
  });

  const restartError = enrollment.isError && endsLogin(enrollment.error) ? enrollment.error : null;
  useEffect(() => {
    if (restartError) onRestart(restartError);
  }, [restartError, onRestart]);

  if (enrollment.isError) return <Alert color="red">{enrollment.error.message}</Alert>;
  const issuer = enrollment.data ? new URL(enrollment.data.uri).searchParams.get('issuer') ?? '' : '';
  const codes = result?.recoveryCodes ?? [];

  return (
    <Stack>
      <Title order={4}>{t('twoFactor.title')}</Title>
      <Text size="sm">{t('twoFactor.intro')}</Text>
      <Stepper active={active} size="xs" allowNextStepsSelect={false}>
        <Stepper.Step label={t('twoFactor.stepScan')}>
          {!enrollment.data ? (
            <Group justify="center" py="xl">
              <Loader />
            </Group>
          ) : (
            <Stack>
              <Text size="sm">{t('twoFactor.scan')}</Text>
              <Box bg="white" p="xs" mx="auto" style={{ borderRadius: 4 }}>
                <Image
                  src={`data:image/svg+xml;charset=utf-8,${encodeURIComponent(enrollment.data.qrCodeSvg)}`}
                  alt={t('twoFactor.qrAlt')}
                  w={220}
                  h={220}
                />
              </Box>
              <Text size="sm">{t('twoFactor.manual')}</Text>
              <Text ff="monospace" fw={600} ta="center" bg="var(--mantine-color-default-hover)" p="xs" style={{ borderRadius: 4 }}>
                {enrollment.data.secret.match(/.{1,4}/g)?.join(' ')}
              </Text>
              <Anchor href={enrollment.data.uri} size="sm" ta="center">
                {t('twoFactor.openApp')}
              </Anchor>
              <Button onClick={() => setActive(1)}>{t('twoFactor.next')}</Button>
            </Stack>
          )}
        </Stepper.Step>
        <Stepper.Step label={t('twoFactor.stepVerify')}>
          <Stack>
            <Text size="sm">{t('twoFactor.verifyIntro', { issuer })}</Text>
            <CodeForm
              label={t('login.code')}
              submit={t('login.verify')}
              loading={confirm.isPending}
              error={confirm.error}
              onSubmit={(code) => confirm.mutate(code)}
            />
            <Anchor component="button" type="button" size="sm" onClick={() => setActive(0)}>
              {t('twoFactor.back')}
            </Anchor>
          </Stack>
        </Stepper.Step>
        <Stepper.Step label={t('twoFactor.stepRecovery')}>
          <Stack>
            <Alert color="green">{t('twoFactor.recoveryIntro')}</Alert>
            <SimpleGrid cols={2} spacing="xs" verticalSpacing={4}>
              {codes.map((c) => (
                <Code key={c} ta="center" fz="sm">
                  {c}
                </Code>
              ))}
            </SimpleGrid>
            <Group grow>
              <CopyButton value={codes.join('\n')}>
                {({ copied, copy }) => (
                  <Button variant="default" onClick={copy}>
                    {copied ? t('twoFactor.copied') : t('twoFactor.copy')}
                  </Button>
                )}
              </CopyButton>
              <Button variant="default" onClick={() => downloadCodes(issuer, result?.user.username ?? '', codes)}>
                {t('twoFactor.download')}
              </Button>
            </Group>
            <Checkbox
              label={t('twoFactor.saved')}
              checked={saved}
              onChange={(e) => setSaved(e.currentTarget.checked)}
            />
            <Button disabled={!saved || !result} onClick={() => result && onDone(result.user)}>
              {t('twoFactor.continue')}
            </Button>
          </Stack>
        </Stepper.Step>
      </Stepper>
    </Stack>
  );
}

function downloadCodes(issuer: string, username: string, codes: string[]) {
  const text = `${issuer} recovery codes for ${username}\nEach code works once.\n\n${codes.join('\n')}\n`;
  const url = URL.createObjectURL(new Blob([text], { type: 'text/plain' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = `${issuer || 'buggywuggy'}-recovery-codes.txt`.toLowerCase().replace(/\s+/g, '-');
  a.click();
  URL.revokeObjectURL(url);
}
