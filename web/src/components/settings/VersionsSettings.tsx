// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { ActionIcon, Alert, Badge, Button, Group, Menu, Modal, Stack, Table, Text, TextInput, Textarea } from '@mantine/core';
import { useForm } from '@mantine/form';
import { IconArrowDown, IconArrowUp, IconDots, IconPlus } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { del, patch, post, put, seg } from '../../api/client';
import { keys, useVersions } from '../../api/queries';
import type { UpdateVersionRequest, VersionResponse } from '../../api/types';
import { fieldErrorMap } from '../../lib/apiError';
import { formatDate } from '../../lib/format';
import { moved } from '../../lib/order';
import { notifyError } from '../../lib/notify';
import { ErrorAlert, PageLoader } from '../QueryState';

type Editing = VersionResponse | 'new' | null;

export function VersionsSettings({ projectKey, readOnly }: { projectKey: string; readOnly: boolean }) {
  const { t, i18n } = useTranslation();
  const versions = useVersions(projectKey);
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<Editing>(null);
  const base = `/api/v1/projects/${seg(projectKey)}/versions`;
  const invalidate = () => void queryClient.invalidateQueries({ queryKey: keys.versions(projectKey) });

  const update = useMutation({
    mutationFn: ({ version, request }: { version: VersionResponse; request: UpdateVersionRequest }) =>
      patch<VersionResponse>(`${base}/${version.id}`, request),
    onSuccess: invalidate,
    onError: notifyError,
  });
  const reorder = useMutation({
    mutationFn: (ordered: VersionResponse[]) => put<VersionResponse[]>(`${base}/order`, { versionIds: ordered.map((v) => v.id) }),
    onMutate: (ordered) => queryClient.setQueryData(keys.versions(projectKey), ordered),
    onSettled: invalidate,
    onError: notifyError,
  });
  const remove = useMutation({
    mutationFn: (version: VersionResponse) => del(`${base}/${version.id}`),
    onSuccess: invalidate,
    onError: notifyError,
  });

  if (versions.isPending) return <PageLoader />;
  if (versions.isError) return <ErrorAlert error={versions.error} />;
  const items = versions.data;

  return (
    <Stack>
      <Group justify="space-between">
        <Text size="sm" c="dimmed">
          {t('settings.versionsIntro')}
        </Text>
        {!readOnly && (
          <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing('new')}>
            {t('settings.version.create')}
          </Button>
        )}
      </Group>
      {items.length === 0 ? (
        <Text c="dimmed">{t('settings.version.empty')}</Text>
      ) : (
        <Table verticalSpacing="sm">
          <Table.Thead>
            <Table.Tr>
              {!readOnly && <Table.Th w={70} />}
              <Table.Th>{t('settings.version.name')}</Table.Th>
              <Table.Th visibleFrom="sm">{t('settings.version.startDate')}</Table.Th>
              <Table.Th>{t('settings.version.releaseDate')}</Table.Th>
              <Table.Th visibleFrom="md">{t('project.description')}</Table.Th>
              {!readOnly && <Table.Th w={40} />}
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {items.map((v, index) => (
              <Table.Tr key={v.id} c={v.archived ? 'dimmed' : undefined}>
                {!readOnly && (
                  <Table.Td>
                    <Group gap={2} wrap="nowrap">
                      <ActionIcon
                        variant="subtle"
                        color="gray"
                        size="sm"
                        disabled={index === 0}
                        aria-label={t('settings.version.moveUp', { name: v.name })}
                        title={t('settings.version.moveUp', { name: v.name })}
                        onClick={() => reorder.mutate(moved(items, index, -1))}
                      >
                        <IconArrowUp size={14} />
                      </ActionIcon>
                      <ActionIcon
                        variant="subtle"
                        color="gray"
                        size="sm"
                        disabled={index === items.length - 1}
                        aria-label={t('settings.version.moveDown', { name: v.name })}
                        title={t('settings.version.moveDown', { name: v.name })}
                        onClick={() => reorder.mutate(moved(items, index, 1))}
                      >
                        <IconArrowDown size={14} />
                      </ActionIcon>
                    </Group>
                  </Table.Td>
                )}
                <Table.Td>
                  <Group gap="xs" wrap="nowrap">
                    <Text fw={500} size="sm">
                      {v.name}
                    </Text>
                    {v.released && (
                      <Badge size="xs" color="green" variant="light">
                        {t('settings.version.released')}
                      </Badge>
                    )}
                    {v.archived && (
                      <Badge size="xs" color="gray" variant="light">
                        {t('settings.version.archived')}
                      </Badge>
                    )}
                  </Group>
                </Table.Td>
                <Table.Td visibleFrom="sm">{formatDate(v.startDate, i18n.language)}</Table.Td>
                <Table.Td>{formatDate(v.releaseDate, i18n.language)}</Table.Td>
                <Table.Td visibleFrom="md">
                  <Text size="sm" lineClamp={2}>
                    {v.description}
                  </Text>
                </Table.Td>
                {!readOnly && (
                  <Table.Td>
                    <Menu position="bottom-end" withinPortal>
                      <Menu.Target>
                        <ActionIcon variant="subtle" color="gray" aria-label={t('common.more')} title={t('common.more')}>
                          <IconDots size={16} />
                        </ActionIcon>
                      </Menu.Target>
                      <Menu.Dropdown>
                        <Menu.Item onClick={() => setEditing(v)}>{t('common.edit')}</Menu.Item>
                        <Menu.Item onClick={() => update.mutate({ version: v, request: { released: !v.released } })}>
                          {v.released ? t('settings.version.unrelease') : t('settings.version.release')}
                        </Menu.Item>
                        <Menu.Item onClick={() => update.mutate({ version: v, request: { archived: !v.archived } })}>
                          {v.archived ? t('settings.version.unarchive') : t('settings.version.archive')}
                        </Menu.Item>
                        <Menu.Divider />
                        <Menu.Item
                          color="red"
                          onClick={() =>
                            window.confirm(t('settings.version.deleteConfirm', { name: v.name })) && remove.mutate(v)
                          }
                        >
                          {t('common.delete')}
                        </Menu.Item>
                      </Menu.Dropdown>
                    </Menu>
                  </Table.Td>
                )}
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      {editing !== null && (
        <VersionModal projectKey={projectKey} version={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />
      )}
    </Stack>
  );
}

interface Values {
  name: string;
  description: string;
  startDate: string;
  releaseDate: string;
}

function VersionModal({
  projectKey,
  version,
  onClose,
}: {
  projectKey: string;
  version: VersionResponse | null;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const form = useForm<Values>({
    initialValues: {
      name: version?.name ?? '',
      description: version?.description ?? '',
      startDate: version?.startDate ?? '',
      releaseDate: version?.releaseDate ?? '',
    },
    validate: {
      name: (v) => (v.trim() ? null : t('validation.required')),
      releaseDate: (v, values) =>
        v && values.startDate && v < values.startDate ? t('settings.version.datesInvalid') : null,
    },
  });
  const base = `/api/v1/projects/${seg(projectKey)}/versions`;
  const save = useMutation({
    mutationFn: (v: Values) => {
      if (!version) {
        return post<VersionResponse>(base, {
          name: v.name.trim(),
          description: v.description.trim() || undefined,
          startDate: v.startDate || undefined,
          releaseDate: v.releaseDate || undefined,
        });
      }
      const request: UpdateVersionRequest = { name: v.name.trim(), description: v.description.trim() };
      if (v.startDate) request.startDate = v.startDate;
      else if (version.startDate) request.clearStartDate = true;
      if (v.releaseDate) request.releaseDate = v.releaseDate;
      else if (version.releaseDate) request.clearReleaseDate = true;
      return patch<VersionResponse>(`${base}/${version.id}`, request);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: keys.versions(projectKey) });
      onClose();
    },
    onError: (e) => form.setErrors(fieldErrorMap(e)),
  });

  return (
    <Modal
      opened
      onClose={onClose}
      title={version ? t('settings.version.editTitle', { name: version.name }) : t('settings.version.create')}
    >
      <form onSubmit={form.onSubmit((v) => save.mutate(v))}>
        <Stack>
          {save.isError && Object.keys(fieldErrorMap(save.error)).length === 0 && (
            <Alert color="red">{save.error.message}</Alert>
          )}
          <TextInput label={t('settings.version.name')} required data-autofocus {...form.getInputProps('name')} />
          <Textarea label={t('project.description')} autosize minRows={2} {...form.getInputProps('description')} />
          <Group grow>
            <TextInput type="date" label={t('settings.version.startDate')} {...form.getInputProps('startDate')} />
            <TextInput type="date" label={t('settings.version.releaseDate')} {...form.getInputProps('releaseDate')} />
          </Group>
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={save.isPending}>
              {version ? t('common.save') : t('common.create')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
