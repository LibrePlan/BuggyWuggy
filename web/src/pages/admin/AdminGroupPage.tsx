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
  Pagination,
  Stack,
  Table,
  Text,
  TextInput,
  Textarea,
  Title,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { IconTrash, IconX } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router';
import { del, patch, put } from '../../api/client';
import { keys, useAdminGroup, useGroupMembers } from '../../api/queries';
import type { GroupResponse, UserRef } from '../../api/types';
import { AnyUserPicker } from '../../components/EntityPickers';
import { ErrorAlert, isNotFound, PageLoader } from '../../components/QueryState';
import { fieldErrorMap } from '../../lib/apiError';
import { notifyError, notifySaved } from '../../lib/notify';
import { NotFoundPage } from '../NotFoundPage';
import { BuiltInBadge } from './AdminGroupsPage';
import { UserBadges } from './AdminUsersPage';

export function AdminGroupPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const id = Number(useParams().groupId);
  const group = useAdminGroup(id);
  const [page, setPage] = useState(0);
  const members = useGroupMembers(id, page);
  const queryClient = useQueryClient();

  const membership = useMutation({
    mutationFn: ({ user, add }: { user: UserRef; add: boolean }) =>
      add ? put(`/api/v1/admin/groups/${id}/members/${user.id}`) : del(`/api/v1/admin/groups/${id}/members/${user.id}`),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: keys.admin }),
    onError: notifyError,
  });
  const remove = useMutation({
    mutationFn: () => del(`/api/v1/admin/groups/${id}`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: keys.admin });
      navigate('/admin/groups', { replace: true });
    },
    onError: notifyError,
  });

  if (group.isPending) return <PageLoader />;
  if (group.isError) return isNotFound(group.error) ? <NotFoundPage /> : <ErrorAlert error={group.error} />;
  const g = group.data;

  return (
    <Stack>
      <Breadcrumbs>
        <Anchor component={Link} to="/admin/groups" size="sm">
          {t('admin.groups')}
        </Anchor>
        <Text size="sm">{g.name}</Text>
      </Breadcrumbs>
      <Group justify="space-between">
        <Group gap="xs">
          <Title order={3}>{g.name}</Title>
          {g.builtIn && <BuiltInBadge />}
        </Group>
        {!g.builtIn && (
          <Button
            color="red"
            variant="light"
            leftSection={<IconTrash size={16} />}
            loading={remove.isPending}
            onClick={() => window.confirm(t('admin.group.deleteConfirm', { name: g.name })) && remove.mutate()}
          >
            {t('common.delete')}
          </Button>
        )}
      </Group>

      <Grid gap="xl">
        <Grid.Col span={{ base: 12, md: 5 }}>
          <GroupDetailsForm group={g} />
        </Grid.Col>
        <Grid.Col span={{ base: 12, md: 7 }}>
          <Stack>
            <Title order={4}>{t('admin.group.membersCount', { count: g.memberCount })}</Title>
            <AnyUserPicker
              placeholder={t('admin.group.addMember')}
              aria-label={t('admin.group.addMember')}
              exclude={members.data?.items.map((u) => u.id)}
              onChange={(user) => user && membership.mutate({ user, add: true })}
            />
            {members.isError ? (
              <ErrorAlert error={members.error} />
            ) : members.data && members.data.items.length > 0 ? (
              <Table>
                <Table.Tbody>
                  {members.data.items.map((u) => (
                    <Table.Tr key={u.id}>
                      <Table.Td>
                        <Group gap="xs" wrap="nowrap">
                          <Anchor component={Link} to={`/admin/users/${u.id}`} size="sm">
                            {u.displayName}
                          </Anchor>
                          <UserBadges user={u} />
                        </Group>
                      </Table.Td>
                      <Table.Td>
                        <Text size="sm" c="dimmed">
                          {u.username}
                        </Text>
                      </Table.Td>
                      <Table.Td w={40}>
                        <ActionIcon
                          variant="subtle"
                          color="gray"
                          size="sm"
                          aria-label={t('admin.group.removeMember', { name: u.displayName })}
                          title={t('admin.group.removeMember', { name: u.displayName })}
                          onClick={() => membership.mutate({ user: u, add: false })}
                        >
                          <IconX size={14} />
                        </ActionIcon>
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            ) : (
              members.data && <Text c="dimmed" size="sm">{t('admin.group.noMembers')}</Text>
            )}
            {members.data && members.data.total > members.data.size && (
              <Pagination
                size="sm"
                total={Math.ceil(members.data.total / members.data.size)}
                value={page + 1}
                onChange={(p) => setPage(p - 1)}
              />
            )}
            <Text size="xs" c="dimmed">
              {t('admin.user.groupsHelp')}
            </Text>
          </Stack>
        </Grid.Col>
      </Grid>
    </Stack>
  );
}

function GroupDetailsForm({ group }: { group: GroupResponse }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const initial = { name: group.name, description: group.description ?? '' };
  const form = useForm({
    initialValues: initial,
    validate: { name: (v) => (v.trim() ? null : t('validation.required')) },
  });
  const { setValues, resetDirty } = form;
  useEffect(() => {
    const values = { name: group.name, description: group.description ?? '' };
    setValues(values);
    resetDirty(values);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [group.name, group.description]);

  const save = useMutation({
    mutationFn: (request: { name?: string; description?: string }) =>
      patch<GroupResponse>(`/api/v1/admin/groups/${group.id}`, request),
    onSuccess: (g) => {
      queryClient.setQueryData(keys.adminGroup(group.id), g);
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
        const request: { name?: string; description?: string } = {};
        if (v.name.trim() !== group.name) request.name = v.name.trim();
        if (v.description.trim() !== (group.description ?? '')) request.description = v.description.trim();
        if (Object.keys(request).length) save.mutate(request);
      })}
    >
      <Stack>
        <Title order={4}>{t('admin.user.details')}</Title>
        <TextInput
          label={t('admin.group.name')}
          required
          disabled={group.builtIn}
          description={group.builtIn ? t('admin.group.builtInHelp') : undefined}
          {...form.getInputProps('name')}
        />
        <Textarea label={t('admin.group.description')} autosize minRows={2} {...form.getInputProps('description')} />
        <Group>
          <Button type="submit" disabled={!form.isDirty()} loading={save.isPending}>
            {t('common.save')}
          </Button>
        </Group>
      </Stack>
    </form>
  );
}
