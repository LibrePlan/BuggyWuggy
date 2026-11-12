// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Button, Grid, Group, PasswordInput, Select, Stack, Switch, Text, TextInput, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { patch, put } from '../api/client';
import { keys, useLanguages, useMyself } from '../api/queries';
import type { MyselfResponse, UpdateMyselfRequest } from '../api/types';
import { PageLoader } from '../components/QueryState';
import { ApiError, fieldErrorMap } from '../lib/apiError';
import { notifyError, notifySaved, PASSWORD_MIN } from '../lib/notify';

/** The logged-in user's own profile and password. */
export function ProfilePage() {
  const { t } = useTranslation();
  const myself = useMyself().data;
  if (!myself) return <PageLoader />;
  return (
    <Stack>
      <Title order={2}>{t('profile.title')}</Title>
      <Grid gap="xl">
        <Grid.Col span={{ base: 12, md: 6 }}>
          <Stack gap="xl">
            <ProfileForm myself={myself} />
            <NotificationSettings myself={myself} />
          </Stack>
        </Grid.Col>
        <Grid.Col span={{ base: 12, md: 6 }}>
          <PasswordForm />
        </Grid.Col>
      </Grid>
    </Stack>
  );
}

function timeZones(): string[] {
  try {
    return Intl.supportedValuesOf('timeZone');
  } catch {
    return [];
  }
}

function ProfileForm({ myself }: { myself: MyselfResponse }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const zones = useMemo(timeZones, []);
  const browserZone = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const languages = useLanguages().data ?? [];
  const valuesOf = (m: MyselfResponse) => ({
    displayName: m.displayName,
    email: m.email ?? '',
    timeZone: m.timeZone ?? '',
    locale: m.locale ?? '',
  });
  const form = useForm({
    initialValues: valuesOf(myself),
    validate: {
      displayName: (v) => (v.trim() ? null : t('validation.required')),
      email: (v) => (/^\S+@\S+$/.test(v.trim()) ? null : t('validation.email')),
    },
  });
  const { setValues, resetDirty } = form;
  useEffect(() => {
    setValues(valuesOf(myself));
    resetDirty(valuesOf(myself));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [myself]);

  const save = useMutation({
    mutationFn: (request: UpdateMyselfRequest) => patch<MyselfResponse>('/api/v1/myself', request),
    onSuccess: (m) => {
      queryClient.setQueryData(keys.myself, m);
      notifySaved();
    },
    onError: (e) => {
      const errors = fieldErrorMap(e);
      if (Object.keys(errors).length) form.setErrors(errors);
      else notifyError(e);
    },
  });

  return (
    <form
      onSubmit={form.onSubmit((v) => {
        const request: UpdateMyselfRequest = {};
        if (v.displayName.trim() !== myself.displayName) request.displayName = v.displayName.trim();
        if (v.email.trim() !== (myself.email ?? '')) request.email = v.email.trim();
        if (v.timeZone !== (myself.timeZone ?? '')) request.timeZone = v.timeZone;
        if (v.locale !== (myself.locale ?? '')) request.locale = v.locale;
        if (Object.keys(request).length) save.mutate(request);
      })}
    >
      <Stack>
        <Title order={4}>{t('profile.details')}</Title>
        <TextInput label={t('admin.user.username')} value={myself.username} disabled readOnly />
        <TextInput label={t('admin.user.displayName')} required {...form.getInputProps('displayName')} />
        <TextInput label={t('admin.user.email')} type="email" required {...form.getInputProps('email')} />
        <Select
          label={t('profile.language')}
          description={t('profile.languageHelp')}
          clearable
          placeholder={t('profile.languageDefault')}
          data={languages.map((l) => ({ value: l.code, label: l.name }))}
          value={form.values.locale || null}
          onChange={(v) => form.setFieldValue('locale', v ?? '')}
          error={form.errors.locale}
        />
        <Select
          label={t('profile.timeZone')}
          description={t('profile.timeZoneHelp', { zone: browserZone })}
          searchable
          clearable
          placeholder={t('profile.timeZoneDefault')}
          data={zones}
          limit={100}
          value={form.values.timeZone || null}
          onChange={(v) => form.setFieldValue('timeZone', v ?? '')}
          error={form.errors.timeZone}
        />
        <Group>
          <Button type="submit" disabled={!form.isDirty()} loading={save.isPending}>
            {t('common.save')}
          </Button>
        </Group>
      </Stack>
    </form>
  );
}

function PasswordForm() {
  const { t } = useTranslation();
  const form = useForm({
    initialValues: { currentPassword: '', newPassword: '', confirm: '' },
    validate: {
      currentPassword: (v) => (v ? null : t('validation.required')),
      newPassword: (v) => (v.length >= PASSWORD_MIN ? null : t('validation.passwordLength', { min: PASSWORD_MIN })),
      confirm: (v, values) => (v === values.newPassword ? null : t('validation.passwordMismatch')),
    },
  });
  const save = useMutation({
    mutationFn: (v: { currentPassword: string; newPassword: string }) => put('/api/v1/myself/password', v),
    onSuccess: () => {
      form.reset();
      notifySaved(t('profile.passwordChanged'));
    },
    onError: (e) => {
      if (e instanceof ApiError && e.code === 'auth.wrong-current-password') {
        form.setErrors({ currentPassword: e.message });
        return;
      }
      const errors = fieldErrorMap(e);
      if (Object.keys(errors).length) form.setErrors(errors);
      else notifyError(e);
    },
  });

  return (
    <form
      onSubmit={form.onSubmit((v) => save.mutate({ currentPassword: v.currentPassword, newPassword: v.newPassword }))}
    >
      <Stack>
        <Title order={4}>{t('profile.changePassword')}</Title>
        <PasswordInput
          label={t('profile.currentPassword')}
          required
          autoComplete="current-password"
          {...form.getInputProps('currentPassword')}
        />
        <PasswordInput
          label={t('admin.user.newPassword')}
          description={t('admin.user.passwordHelp', { min: PASSWORD_MIN })}
          required
          autoComplete="new-password"
          {...form.getInputProps('newPassword')}
        />
        <PasswordInput
          label={t('admin.user.confirmPassword')}
          required
          autoComplete="new-password"
          {...form.getInputProps('confirm')}
        />
        <Text size="sm" c="dimmed">
          {t('profile.passwordHelp')}
        </Text>
        <Group>
          <Button type="submit" loading={save.isPending}>
            {t('profile.changePassword')}
          </Button>
        </Group>
      </Stack>
    </form>
  );
}

/** Switches that are saved right away. */
function NotificationSettings({ myself }: { myself: MyselfResponse }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const save = useMutation({
    mutationFn: (request: UpdateMyselfRequest) => patch<MyselfResponse>('/api/v1/myself', request),
    onSuccess: (m) => queryClient.setQueryData(keys.myself, m),
    onError: notifyError,
  });
  return (
    <Stack>
      <Title order={4}>{t('profile.notifications')}</Title>
      <Switch
        label={t('profile.emailNotifications')}
        description={t('profile.emailNotificationsHelp')}
        checked={myself.emailNotifications}
        disabled={save.isPending}
        onChange={(e) => save.mutate({ emailNotifications: e.currentTarget.checked })}
      />
      <Switch
        label={t('profile.notifyOwnChanges')}
        description={t('profile.notifyOwnChangesHelp')}
        checked={myself.notifyOwnChanges}
        disabled={save.isPending || !myself.emailNotifications}
        onChange={(e) => save.mutate({ notifyOwnChanges: e.currentTarget.checked })}
      />
    </Stack>
  );
}
