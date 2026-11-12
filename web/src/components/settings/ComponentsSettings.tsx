// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { ActionIcon, Alert, Button, Group, Modal, Select, Stack, Table, Text, TextInput, Textarea } from '@mantine/core';
import { useForm } from '@mantine/form';
import { IconPencil, IconPlus, IconTrash } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { del, patch, post, seg } from '../../api/client';
import { keys, useComponents } from '../../api/queries';
import type { ComponentResponse, UpdateComponentRequest, UserRef } from '../../api/types';
import { fieldErrorMap } from '../../lib/apiError';
import { notifyError } from '../../lib/notify';
import { AnyUserPicker } from '../EntityPickers';
import { ErrorAlert, PageLoader } from '../QueryState';
import { UserLabel } from '../UserLabel';

type DefaultAssignee = ComponentResponse['defaultAssignee'];
const ASSIGNEES: DefaultAssignee[] = ['PROJECT_DEFAULT', 'COMPONENT_LEAD', 'PROJECT_LEAD', 'UNASSIGNED'];

/** Editing state: {@code null} = closed, {@code 'new'} = creating. */
type Editing = ComponentResponse | 'new' | null;

export function ComponentsSettings({ projectKey, readOnly }: { projectKey: string; readOnly: boolean }) {
  const { t } = useTranslation();
  const components = useComponents(projectKey);
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState<Editing>(null);
  const remove = useMutation({
    mutationFn: (c: ComponentResponse) => del(`/api/v1/projects/${seg(projectKey)}/components/${c.id}`),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: keys.components(projectKey) }),
    onError: notifyError,
  });

  if (components.isPending) return <PageLoader />;
  if (components.isError) return <ErrorAlert error={components.error} />;

  return (
    <Stack>
      <Group justify="space-between">
        <Text size="sm" c="dimmed">
          {t('settings.componentsIntro')}
        </Text>
        {!readOnly && (
          <Button leftSection={<IconPlus size={16} />} onClick={() => setEditing('new')}>
            {t('settings.component.create')}
          </Button>
        )}
      </Group>
      {components.data.length === 0 ? (
        <Text c="dimmed">{t('settings.component.empty')}</Text>
      ) : (
        <Table verticalSpacing="sm">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('settings.component.name')}</Table.Th>
              <Table.Th visibleFrom="sm">{t('project.description')}</Table.Th>
              <Table.Th>{t('project.lead')}</Table.Th>
              <Table.Th visibleFrom="md">{t('settings.defaultAssignee')}</Table.Th>
              {!readOnly && <Table.Th w={80} />}
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {components.data.map((c) => (
              <Table.Tr key={c.id}>
                <Table.Td fw={500}>{c.name}</Table.Td>
                <Table.Td visibleFrom="sm">
                  <Text size="sm" lineClamp={2}>
                    {c.description}
                  </Text>
                </Table.Td>
                <Table.Td>
                  <UserLabel user={c.lead} emptyLabel="—" />
                </Table.Td>
                <Table.Td visibleFrom="md">
                  <Text size="sm">{t(`settings.assignee.${c.defaultAssignee}`)}</Text>
                </Table.Td>
                {!readOnly && (
                  <Table.Td>
                    <Group gap={4} wrap="nowrap">
                      <ActionIcon
                        variant="subtle"
                        color="gray"
                        aria-label={t('common.edit')}
                        title={t('common.edit')}
                        onClick={() => setEditing(c)}
                      >
                        <IconPencil size={16} />
                      </ActionIcon>
                      <ActionIcon
                        variant="subtle"
                        color="gray"
                        aria-label={t('common.delete')}
                        title={t('common.delete')}
                        loading={remove.isPending && remove.variables?.id === c.id}
                        onClick={() =>
                          window.confirm(t('settings.component.deleteConfirm', { name: c.name })) && remove.mutate(c)
                        }
                      >
                        <IconTrash size={16} />
                      </ActionIcon>
                    </Group>
                  </Table.Td>
                )}
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      {editing !== null && (
        <ComponentModal projectKey={projectKey} component={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />
      )}
    </Stack>
  );
}

interface Values {
  name: string;
  description: string;
  lead: UserRef | null;
  defaultAssignee: DefaultAssignee;
}

function ComponentModal({
  projectKey,
  component,
  onClose,
}: {
  projectKey: string;
  component: ComponentResponse | null;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const form = useForm<Values>({
    initialValues: {
      name: component?.name ?? '',
      description: component?.description ?? '',
      lead: component?.lead ?? null,
      defaultAssignee: component?.defaultAssignee ?? 'PROJECT_DEFAULT',
    },
    validate: {
      name: (v) => (v.trim() ? null : t('validation.required')),
      defaultAssignee: (v, values) =>
        v === 'COMPONENT_LEAD' && !values.lead ? t('settings.component.leadRequired') : null,
    },
  });
  const base = `/api/v1/projects/${seg(projectKey)}/components`;
  const save = useMutation({
    mutationFn: (v: Values) => {
      if (!component) {
        return post<ComponentResponse>(base, {
          name: v.name.trim(),
          description: v.description.trim() || undefined,
          leadId: v.lead?.id,
          defaultAssignee: v.defaultAssignee,
        });
      }
      const request: UpdateComponentRequest = {
        name: v.name.trim(),
        description: v.description.trim(),
        defaultAssignee: v.defaultAssignee,
      };
      if (v.lead) request.leadId = v.lead.id;
      else if (component.lead) request.clearLead = true;
      return patch<ComponentResponse>(`${base}/${component.id}`, request);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: keys.components(projectKey) });
      onClose();
    },
    onError: (e) => form.setErrors(fieldErrorMap(e)),
  });

  return (
    <Modal
      opened
      onClose={onClose}
      title={component ? t('settings.component.editTitle', { name: component.name }) : t('settings.component.create')}
    >
      <form onSubmit={form.onSubmit((v) => save.mutate(v))}>
        <Stack>
          {save.isError && Object.keys(fieldErrorMap(save.error)).length === 0 && (
            <Alert color="red">{save.error.message}</Alert>
          )}
          <TextInput label={t('settings.component.name')} required data-autofocus {...form.getInputProps('name')} />
          <Textarea label={t('project.description')} autosize minRows={2} {...form.getInputProps('description')} />
          <AnyUserPicker
            label={t('project.lead')}
            value={form.values.lead}
            onChange={(u) => form.setFieldValue('lead', u)}
          />
          <Select
            label={t('settings.defaultAssignee')}
            allowDeselect={false}
            data={ASSIGNEES.map((a) => ({ value: a, label: t(`settings.assignee.${a}`) }))}
            {...form.getInputProps('defaultAssignee')}
          />
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={save.isPending}>
              {component ? t('common.save') : t('common.create')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
