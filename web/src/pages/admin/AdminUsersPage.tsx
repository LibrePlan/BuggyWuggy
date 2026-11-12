// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import {
  Alert,
  Anchor,
  Badge,
  Button,
  Checkbox,
  Group,
  Modal,
  Pagination,
  PasswordInput,
  Stack,
  Table,
  Text,
  TextInput,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { useDebouncedValue, useDisclosure } from '@mantine/hooks';
import { IconPlus, IconSearch } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { post } from '../../api/client';
import { keys, useAdminUsers, useMyself } from '../../api/queries';
import type { CreateUserRequest, UserResponse } from '../../api/types';
import { ErrorAlert, PageLoader } from '../../components/QueryState';
import { fieldErrorMap } from '../../lib/apiError';
import { formatDateTime } from '../../lib/format';
import { PASSWORD_MIN } from '../../lib/notify';

export function AdminUsersPage() {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const [query] = useDebouncedValue(search.trim(), 250);
  const [page, setPage] = useState(0);
  const users = useAdminUsers(query, page);
  const [creating, createModal] = useDisclosure(false);

  return (
    <Stack>
      <Group justify="space-between">
        <TextInput
          placeholder={t('admin.user.search')}
          leftSection={<IconSearch size={16} />}
          value={search}
          onChange={(e) => {
            setSearch(e.currentTarget.value);
            setPage(0);
          }}
          w={360}
          maw="100%"
        />
        <Button leftSection={<IconPlus size={16} />} onClick={createModal.open}>
          {t('admin.user.create')}
        </Button>
      </Group>
      {users.isPending ? (
        <PageLoader />
      ) : users.isError ? (
        <ErrorAlert error={users.error} />
      ) : users.data.items.length === 0 ? (
        <Text c="dimmed">{t('common.nothingFound')}</Text>
      ) : (
        <>
          <Table highlightOnHover verticalSpacing="sm">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('admin.user.displayName')}</Table.Th>
                <Table.Th>{t('admin.user.username')}</Table.Th>
                <Table.Th visibleFrom="sm">{t('admin.user.email')}</Table.Th>
                <Table.Th visibleFrom="md">{t('admin.user.groups')}</Table.Th>
                <Table.Th visibleFrom="md">{t('admin.user.lastLogin')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {users.data.items.map((u) => (
                <Table.Tr key={u.id} className="bw-row-link" onClick={() => navigate(`/admin/users/${u.id}`)}>
                  <Table.Td>
                    <Group gap="xs" wrap="nowrap">
                      <Anchor
                        component={Link}
                        to={`/admin/users/${u.id}`}
                        fw={500}
                        onClick={(e) => e.stopPropagation()}
                      >
                        {u.displayName}
                      </Anchor>
                      <UserBadges user={u} />
                    </Group>
                  </Table.Td>
                  <Table.Td>{u.username}</Table.Td>
                  <Table.Td visibleFrom="sm">{u.email}</Table.Td>
                  <Table.Td visibleFrom="md">
                    <Text size="sm" lineClamp={1}>
                      {u.groups.map((g) => g.name).join(', ')}
                    </Text>
                  </Table.Td>
                  <Table.Td visibleFrom="md">
                    <Text size="sm" c="dimmed">
                      {u.lastLoginAt ? formatDateTime(u.lastLoginAt, i18n.language) : t('admin.user.never')}
                    </Text>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
          <Group justify="space-between">
            <Text size="sm" c="dimmed">
              {t('admin.user.count', { count: users.data.total })}
            </Text>
            {users.data.total > users.data.size && (
              <Pagination
                total={Math.ceil(users.data.total / users.data.size)}
                value={page + 1}
                onChange={(p) => setPage(p - 1)}
              />
            )}
          </Group>
        </>
      )}
      <CreateUserModal opened={creating} onClose={createModal.close} />
    </Stack>
  );
}

export function UserBadges({ user }: { user: UserResponse }) {
  const { t } = useTranslation();
  return (
    <>
      {!user.active && (
        <Badge size="xs" color="gray" variant="light">
          {t('admin.user.inactive')}
        </Badge>
      )}
      {user.systemAdmin && (
        <Badge size="xs" color="grape" variant="light">
          {t('admin.user.systemAdmin')}
        </Badge>
      )}
    </>
  );
}

function CreateUserModal({ opened, onClose }: { opened: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const myself = useMyself().data;
  const form = useForm({
    initialValues: { username: '', displayName: '', email: '', password: '', systemAdmin: false },
    validate: {
      username: (v) => (v.trim() ? null : t('validation.required')),
      displayName: (v) => (v.trim() ? null : t('validation.required')),
      email: (v) => (/^\S+@\S+$/.test(v.trim()) ? null : t('validation.email')),
      password: (v) => (v.length >= PASSWORD_MIN ? null : t('validation.passwordLength', { min: PASSWORD_MIN })),
    },
  });

  const create = useMutation({
    mutationFn: (request: CreateUserRequest) => post<UserResponse>('/api/v1/admin/users', request),
    onSuccess: (user) => {
      void queryClient.invalidateQueries({ queryKey: keys.admin });
      close();
      navigate(`/admin/users/${user.id}`);
    },
    onError: (e) => form.setErrors(fieldErrorMap(e)),
  });

  function close() {
    form.reset();
    create.reset();
    onClose();
  }

  return (
    <Modal opened={opened} onClose={close} title={t('admin.user.create')}>
      <form
        onSubmit={form.onSubmit((v) =>
          create.mutate({
            username: v.username.trim(),
            displayName: v.displayName.trim(),
            email: v.email.trim(),
            password: v.password,
            systemAdmin: v.systemAdmin,
          }),
        )}
      >
        <Stack>
          {create.isError && Object.keys(fieldErrorMap(create.error)).length === 0 && (
            <Alert color="red">{create.error.message}</Alert>
          )}
          <TextInput label={t('admin.user.username')} required data-autofocus {...form.getInputProps('username')} />
          <TextInput label={t('admin.user.displayName')} required {...form.getInputProps('displayName')} />
          <TextInput label={t('admin.user.email')} type="email" required {...form.getInputProps('email')} />
          <PasswordInput
            label={t('admin.user.password')}
            description={t('admin.user.passwordHelp', { min: PASSWORD_MIN })}
            required
            autoComplete="new-password"
            {...form.getInputProps('password')}
          />
          {myself?.systemAdmin && (
            <Checkbox
              label={t('admin.user.systemAdmin')}
              description={t('admin.user.systemAdminHelp')}
              {...form.getInputProps('systemAdmin', { type: 'checkbox' })}
            />
          )}
          <Text size="sm" c="dimmed">
            {t('admin.user.createHelp')}
          </Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={close}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={create.isPending}>
              {t('common.create')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
