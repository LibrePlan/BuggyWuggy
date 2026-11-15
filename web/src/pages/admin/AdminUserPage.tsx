// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import {
  ActionIcon,
  Anchor,
  Breadcrumbs,
  Button,
  Grid,
  Group,
  Modal,
  Paper,
  PasswordInput,
  Stack,
  Switch,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { useDisclosure } from '@mantine/hooks';
import { IconKey, IconShieldOff, IconX } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router';
import { del, patch, put } from '../../api/client';
import { keys, useAdminUser, useMyself } from '../../api/queries';
import type { GroupRef, UpdateUserRequest, UserResponse } from '../../api/types';
import { GroupPicker } from '../../components/EntityPickers';
import { ErrorAlert, isNotFound, PageLoader } from '../../components/QueryState';
import { fieldErrorMap } from '../../lib/apiError';
import { formatDateTime } from '../../lib/format';
import { notifyError, notifySaved, PASSWORD_MIN } from '../../lib/notify';
import { NotFoundPage } from '../NotFoundPage';
import { UserBadges } from './AdminUsersPage';

export function AdminUserPage() {
  const { t, i18n } = useTranslation();
  const id = Number(useParams().userId);
  const user = useAdminUser(id);
  const myself = useMyself().data;
  const queryClient = useQueryClient();
  const [settingPassword, passwordModal] = useDisclosure(false);
  const [resettingTwoFactor, resetTwoFactorModal] = useDisclosure(false);

  const refresh = (updated?: UserResponse) => {
    if (updated) queryClient.setQueryData(keys.adminUser(id), updated);
    void queryClient.invalidateQueries({ queryKey: keys.admin });
  };
  const update = useMutation({
    mutationFn: (request: UpdateUserRequest) => patch<UserResponse>(`/api/v1/admin/users/${id}`, request),
    onSuccess: (u) => {
      refresh(u);
      notifySaved();
    },
    onError: notifyError,
  });
  const membership = useMutation({
    mutationFn: ({ group, add }: { group: GroupRef; add: boolean }) =>
      add ? put(`/api/v1/admin/groups/${group.id}/members/${id}`) : del(`/api/v1/admin/groups/${group.id}/members/${id}`),
    onSuccess: () => refresh(),
    onError: notifyError,
  });

  if (user.isPending) return <PageLoader />;
  if (user.isError) return isNotFound(user.error) ? <NotFoundPage /> : <ErrorAlert error={user.error} />;
  const u = user.data;
  const self = myself?.id === u.id;
  // Only system administrators may manage system administrators (the server checks this too).
  const mayManage = !u.systemAdmin || (myself?.systemAdmin ?? false);

  return (
    <Stack>
      <Breadcrumbs>
        <Anchor component={Link} to="/admin/users" size="sm">
          {t('admin.users')}
        </Anchor>
        <Text size="sm">{u.username}</Text>
      </Breadcrumbs>
      <Group gap="xs">
        <Title order={3}>{u.displayName}</Title>
        <UserBadges user={u} />
      </Group>
      <Text size="sm" c="dimmed">
        {t('admin.user.created', { date: formatDateTime(u.createdAt, i18n.language) })} ·{' '}
        {t('admin.user.lastLoginAt', {
          date: u.lastLoginAt ? formatDateTime(u.lastLoginAt, i18n.language) : t('admin.user.never'),
        })}
      </Text>

      <Grid gap="xl">
        <Grid.Col span={{ base: 12, md: 6 }}>
          <Stack>
            <Title order={4}>{t('admin.user.details')}</Title>
            <UserDetailsForm user={u} disabled={!mayManage} />
            <Switch
              label={t('admin.user.active')}
              description={t('admin.user.activeHelp')}
              checked={u.active}
              disabled={!mayManage || self || update.isPending}
              onChange={(e) => update.mutate({ active: e.currentTarget.checked })}
            />
            <Switch
              label={t('admin.user.systemAdmin')}
              description={t('admin.user.systemAdminHelp')}
              checked={u.systemAdmin}
              disabled={!myself?.systemAdmin || self || update.isPending}
              onChange={(e) => update.mutate({ systemAdmin: e.currentTarget.checked })}
            />
            <Group>
              <Button
                variant="default"
                leftSection={<IconKey size={16} />}
                disabled={!mayManage}
                onClick={passwordModal.open}
              >
                {t('admin.user.setPassword')}
              </Button>
            </Group>
            <Title order={4}>{t('admin.user.twoFactor')}</Title>
            <Text size="sm">{u.twoFactorEnrolled ? t('admin.user.twoFactorEnrolled') : t('admin.user.twoFactorNotEnrolled')}</Text>
            <Group>
              <Button
                variant="default"
                color="red"
                leftSection={<IconShieldOff size={16} />}
                disabled={!mayManage || !u.twoFactorEnrolled}
                onClick={resetTwoFactorModal.open}
              >
                {t('admin.user.resetTwoFactor')}
              </Button>
            </Group>
          </Stack>
        </Grid.Col>
        <Grid.Col span={{ base: 12, md: 6 }}>
          <Stack>
            <Title order={4}>{t('admin.user.groups')}</Title>
            <Paper withBorder>
              <Table>
                <Table.Tbody>
                  {u.groups.map((g) => (
                    <Table.Tr key={g.id}>
                      <Table.Td>
                        <Anchor component={Link} to={`/admin/groups/${g.id}`} size="sm">
                          {g.name}
                        </Anchor>
                      </Table.Td>
                      <Table.Td w={40}>
                        <ActionIcon
                          variant="subtle"
                          color="gray"
                          size="sm"
                          aria-label={t('admin.user.leaveGroup', { group: g.name })}
                          title={t('admin.user.leaveGroup', { group: g.name })}
                          onClick={() => membership.mutate({ group: g, add: false })}
                        >
                          <IconX size={14} />
                        </ActionIcon>
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Paper>
            <GroupPicker
              placeholder={t('admin.user.addToGroup')}
              aria-label={t('admin.user.addToGroup')}
              exclude={u.groups.map((g) => g.id)}
              onChange={(group) => group && membership.mutate({ group, add: true })}
            />
            <Text size="xs" c="dimmed">
              {t('admin.user.groupsHelp')}
            </Text>
          </Stack>
        </Grid.Col>
      </Grid>
      <SetPasswordModal user={u} opened={settingPassword} onClose={passwordModal.close} />
      <ResetTwoFactorModal user={u} opened={resettingTwoFactor} onClose={resetTwoFactorModal.close} />
    </Stack>
  );
}

function UserDetailsForm({ user, disabled }: { user: UserResponse; disabled: boolean }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const form = useForm({
    initialValues: { displayName: user.displayName, email: user.email ?? '' },
    validate: {
      displayName: (v) => (v.trim() ? null : t('validation.required')),
      email: (v) => (/^\S+@\S+$/.test(v.trim()) ? null : t('validation.email')),
    },
  });
  const { setValues, resetDirty } = form;
  useEffect(() => {
    setValues({ displayName: user.displayName, email: user.email ?? '' });
    resetDirty({ displayName: user.displayName, email: user.email ?? '' });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user.displayName, user.email]);

  const save = useMutation({
    mutationFn: (request: UpdateUserRequest) => patch<UserResponse>(`/api/v1/admin/users/${user.id}`, request),
    onSuccess: (u) => {
      queryClient.setQueryData(keys.adminUser(user.id), u);
      void queryClient.invalidateQueries({ queryKey: keys.admin });
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
        const request: UpdateUserRequest = {};
        if (v.displayName.trim() !== user.displayName) request.displayName = v.displayName.trim();
        if (v.email.trim() !== (user.email ?? '')) request.email = v.email.trim();
        if (Object.keys(request).length) save.mutate(request);
      })}
    >
      <Stack>
        <TextInput label={t('admin.user.username')} value={user.username} disabled readOnly />
        <TextInput label={t('admin.user.displayName')} required disabled={disabled} {...form.getInputProps('displayName')} />
        <TextInput label={t('admin.user.email')} type="email" required disabled={disabled} {...form.getInputProps('email')} />
        <Group>
          <Button type="submit" disabled={disabled || !form.isDirty()} loading={save.isPending}>
            {t('common.save')}
          </Button>
        </Group>
      </Stack>
    </form>
  );
}

function SetPasswordModal({ user, opened, onClose }: { user: UserResponse; opened: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  const form = useForm({
    initialValues: { password: '', confirm: '' },
    validate: {
      password: (v) => (v.length >= PASSWORD_MIN ? null : t('validation.passwordLength', { min: PASSWORD_MIN })),
      confirm: (v, values) => (v === values.password ? null : t('validation.passwordMismatch')),
    },
  });
  const save = useMutation({
    mutationFn: (password: string) => put(`/api/v1/admin/users/${user.id}/password`, { password }),
    onSuccess: () => {
      notifySaved(t('admin.user.passwordSet', { name: user.displayName }));
      close();
    },
    onError: (e) => form.setErrors({ password: e.message }),
  });

  function close() {
    form.reset();
    save.reset();
    onClose();
  }

  return (
    <Modal opened={opened} onClose={close} title={t('admin.user.setPasswordFor', { name: user.displayName })}>
      <form onSubmit={form.onSubmit((v) => save.mutate(v.password))}>
        <Stack>
          <PasswordInput
            label={t('admin.user.newPassword')}
            description={t('admin.user.passwordHelp', { min: PASSWORD_MIN })}
            required
            data-autofocus
            autoComplete="new-password"
            {...form.getInputProps('password')}
          />
          <PasswordInput
            label={t('admin.user.confirmPassword')}
            required
            autoComplete="new-password"
            {...form.getInputProps('confirm')}
          />
          <Text size="sm" c="dimmed">
            {t('admin.user.setPasswordHelp')}
          </Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={close}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={save.isPending}>
              {t('admin.user.setPassword')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}

/** Forgets the user's second factor, e.g. after losing the phone: the next login sets it up again. */
function ResetTwoFactorModal({ user, opened, onClose }: { user: UserResponse; opened: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const reset = useMutation({
    mutationFn: () => del(`/api/v1/admin/users/${user.id}/two-factor`),
    onSuccess: () => {
      notifySaved(t('admin.user.twoFactorReset', { name: user.displayName }));
      void queryClient.invalidateQueries({ queryKey: keys.admin });
      onClose();
    },
    onError: notifyError,
  });
  return (
    <Modal opened={opened} onClose={onClose} title={t('admin.user.resetTwoFactorFor', { name: user.displayName })}>
      <Stack>
        <Text size="sm">{t('admin.user.resetTwoFactorText', { name: user.displayName })}</Text>
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button color="red" loading={reset.isPending} onClick={() => reset.mutate()}>
            {t('admin.user.resetTwoFactorConfirm')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
