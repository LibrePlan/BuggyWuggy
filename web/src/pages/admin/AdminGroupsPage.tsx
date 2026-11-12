// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Alert, Anchor, Badge, Button, Group, Modal, Pagination, Stack, Table, Text, TextInput, Textarea } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useDebouncedValue, useDisclosure } from '@mantine/hooks';
import { IconPlus, IconSearch } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { post } from '../../api/client';
import { keys, useAdminGroups } from '../../api/queries';
import type { GroupResponse } from '../../api/types';
import { ErrorAlert, PageLoader } from '../../components/QueryState';
import { fieldErrorMap } from '../../lib/apiError';

export function AdminGroupsPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const [search, setSearch] = useState('');
  const [query] = useDebouncedValue(search.trim(), 250);
  const [page, setPage] = useState(0);
  const groups = useAdminGroups(query, page);
  const [creating, createModal] = useDisclosure(false);

  return (
    <Stack>
      <Group justify="space-between">
        <TextInput
          placeholder={t('admin.group.search')}
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
          {t('admin.group.create')}
        </Button>
      </Group>
      {groups.isPending ? (
        <PageLoader />
      ) : groups.isError ? (
        <ErrorAlert error={groups.error} />
      ) : groups.data.items.length === 0 ? (
        <Text c="dimmed">{t('common.nothingFound')}</Text>
      ) : (
        <>
          <Table highlightOnHover verticalSpacing="sm">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('admin.group.name')}</Table.Th>
                <Table.Th visibleFrom="sm">{t('admin.group.description')}</Table.Th>
                <Table.Th w={120}>{t('admin.group.members')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {groups.data.items.map((g) => (
                <Table.Tr key={g.id} className="bw-row-link" onClick={() => navigate(`/admin/groups/${g.id}`)}>
                  <Table.Td>
                    <Group gap="xs" wrap="nowrap">
                      <Anchor
                        component={Link}
                        to={`/admin/groups/${g.id}`}
                        fw={500}
                        onClick={(e) => e.stopPropagation()}
                      >
                        {g.name}
                      </Anchor>
                      {g.builtIn && <BuiltInBadge />}
                    </Group>
                  </Table.Td>
                  <Table.Td visibleFrom="sm">
                    <Text size="sm" lineClamp={1}>
                      {g.description}
                    </Text>
                  </Table.Td>
                  <Table.Td>{g.memberCount}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
          {groups.data.total > groups.data.size && (
            <Pagination
              total={Math.ceil(groups.data.total / groups.data.size)}
              value={page + 1}
              onChange={(p) => setPage(p - 1)}
            />
          )}
        </>
      )}
      <CreateGroupModal opened={creating} onClose={createModal.close} />
    </Stack>
  );
}

export function BuiltInBadge() {
  const { t } = useTranslation();
  return (
    <Badge size="xs" color="gray" variant="light" title={t('admin.group.builtInHelp')}>
      {t('admin.group.builtIn')}
    </Badge>
  );
}

function CreateGroupModal({ opened, onClose }: { opened: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const form = useForm({
    initialValues: { name: '', description: '' },
    validate: { name: (v) => (v.trim() ? null : t('validation.required')) },
  });
  const create = useMutation({
    mutationFn: (v: { name: string; description: string }) =>
      post<GroupResponse>('/api/v1/admin/groups', {
        name: v.name.trim(),
        description: v.description.trim() || undefined,
      }),
    onSuccess: (group) => {
      void queryClient.invalidateQueries({ queryKey: keys.admin });
      close();
      navigate(`/admin/groups/${group.id}`);
    },
    onError: (e) => form.setErrors(fieldErrorMap(e)),
  });

  function close() {
    form.reset();
    create.reset();
    onClose();
  }

  return (
    <Modal opened={opened} onClose={close} title={t('admin.group.create')}>
      <form onSubmit={form.onSubmit((v) => create.mutate(v))}>
        <Stack>
          {create.isError && Object.keys(fieldErrorMap(create.error)).length === 0 && (
            <Alert color="red">{create.error.message}</Alert>
          )}
          <TextInput label={t('admin.group.name')} required data-autofocus {...form.getInputProps('name')} />
          <Textarea label={t('admin.group.description')} autosize minRows={2} {...form.getInputProps('description')} />
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
