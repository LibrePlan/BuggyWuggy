// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Button, Divider, Group, Modal, Paper, Select, Stack, Text, TextInput, Textarea, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useDisclosure } from '@mantine/hooks';
import { IconArchive, IconArchiveOff, IconTrash } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { del, patch, seg } from '../../api/client';
import { keys } from '../../api/queries';
import type { ProjectResponse, UpdateProjectRequest, UserRef } from '../../api/types';
import { fieldErrorMap } from '../../lib/apiError';
import { notifyError, notifySaved } from '../../lib/notify';
import { PROJECT_KEY_PATTERN } from '../../lib/projectKey';
import { AnyUserPicker } from '../EntityPickers';

type DefaultAssignee = ProjectResponse['defaultAssignee'];

interface Values {
  name: string;
  key: string;
  description: string;
  lead: UserRef | null;
  defaultAssignee: DefaultAssignee;
}

const valuesOf = (p: ProjectResponse): Values => ({
  name: p.name,
  key: p.key,
  description: p.description ?? '',
  lead: p.lead,
  defaultAssignee: p.defaultAssignee,
});

/** Name, key, description, lead and default assignee; and archiving and deleting. */
export function ProjectDetailsSettings({ project, globalAdmin }: { project: ProjectResponse; globalAdmin: boolean }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const form = useForm<Values>({
    initialValues: valuesOf(project),
    validate: {
      name: (v) => (v.trim() ? null : t('validation.required')),
      key: (v) => (PROJECT_KEY_PATTERN.test(v) ? null : t('validation.projectKey')),
    },
  });
  const { setValues, resetDirty } = form;
  useEffect(() => {
    setValues(valuesOf(project));
    resetDirty(valuesOf(project));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [project]);

  const onSaved = (p: ProjectResponse) => {
    void queryClient.invalidateQueries({ queryKey: keys.allProjects });
    void queryClient.invalidateQueries({ queryKey: keys.project(project.key) });
    if (p.key !== project.key) {
      // Issue keys change with the project key.
      void queryClient.invalidateQueries({ queryKey: ['issue'] });
      void queryClient.invalidateQueries({ queryKey: keys.allSearches });
      navigate(`/projects/${p.key}/settings`, { replace: true });
    }
  };
  const save = useMutation({
    mutationFn: (request: UpdateProjectRequest) => patch<ProjectResponse>(`/api/v1/projects/${seg(project.key)}`, request),
    onSuccess: (p) => {
      onSaved(p);
      notifySaved();
    },
    onError: (e) => {
      const errors = fieldErrorMap(e);
      if (Object.keys(errors).length) form.setErrors(errors);
      else notifyError(e);
    },
  });

  function submit(v: Values) {
    const request: UpdateProjectRequest = {};
    if (v.name.trim() !== project.name) request.name = v.name.trim();
    if (v.key !== project.key) request.key = v.key;
    if (v.description.trim() !== (project.description ?? '')) request.description = v.description.trim();
    if (v.lead && v.lead.id !== project.lead?.id) request.leadId = v.lead.id;
    if (v.defaultAssignee !== project.defaultAssignee) request.defaultAssignee = v.defaultAssignee;
    if (Object.keys(request).length) save.mutate(request);
  }

  const archived = project.archived;
  return (
    <Stack maw={640}>
      <form onSubmit={form.onSubmit(submit)}>
        <Stack>
          <TextInput label={t('project.name')} required disabled={archived} {...form.getInputProps('name')} />
          <TextInput
            label={t('project.key')}
            description={globalAdmin ? t('settings.keyHelp') : t('settings.keyAdminOnly')}
            required
            maxLength={10}
            disabled={archived || !globalAdmin}
            {...form.getInputProps('key')}
            onChange={(e) => form.setFieldValue('key', e.currentTarget.value.toUpperCase())}
          />
          <Textarea
            label={t('project.description')}
            autosize
            minRows={3}
            disabled={archived}
            {...form.getInputProps('description')}
          />
          <AnyUserPicker
            label={t('project.lead')}
            required
            disabled={archived}
            value={form.values.lead}
            onChange={(u) => form.setFieldValue('lead', u)}
          />
          <Select
            label={t('settings.defaultAssignee')}
            description={t('settings.defaultAssigneeHelp')}
            disabled={archived}
            allowDeselect={false}
            data={[
              { value: 'UNASSIGNED', label: t('settings.assignee.UNASSIGNED') },
              { value: 'PROJECT_LEAD', label: t('settings.assignee.PROJECT_LEAD') },
            ]}
            value={form.values.defaultAssignee}
            onChange={(v) => v && form.setFieldValue('defaultAssignee', v as DefaultAssignee)}
          />
          <Group>
            <Button type="submit" disabled={archived || !form.isDirty()} loading={save.isPending}>
              {t('common.save')}
            </Button>
          </Group>
        </Stack>
      </form>
      {globalAdmin && <DangerZone project={project} onSaved={onSaved} />}
    </Stack>
  );
}

function DangerZone({ project, onSaved }: { project: ProjectResponse; onSaved: (p: ProjectResponse) => void }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [deleting, deleteModal] = useDisclosure(false);
  const archive = useMutation({
    mutationFn: (archived: boolean) => patch<ProjectResponse>(`/api/v1/projects/${seg(project.key)}`, { archived }),
    onSuccess: (p) => {
      onSaved(p);
      void queryClient.invalidateQueries({ queryKey: keys.projectPermissions(project.key) });
      notifySaved(p.archived ? t('settings.archivedDone') : t('settings.unarchivedDone'));
    },
    onError: notifyError,
  });
  const remove = useMutation({
    mutationFn: () => del(`/api/v1/projects/${seg(project.key)}`),
    onSuccess: () => {
      queryClient.removeQueries({ queryKey: keys.project(project.key) });
      void queryClient.invalidateQueries({ queryKey: keys.allProjects });
      void queryClient.invalidateQueries({ queryKey: keys.allSearches });
      notifySaved(t('settings.deleted', { name: project.name }));
      navigate('/projects', { replace: true });
    },
    onError: notifyError,
  });

  return (
    <>
      <Divider my="md" />
      <Title order={4}>{t('settings.dangerZone')}</Title>
      <Paper withBorder p="md">
        <Stack>
          <Group justify="space-between" wrap="nowrap">
            <div>
              <Text fw={500}>{project.archived ? t('settings.unarchive') : t('settings.archive')}</Text>
              <Text size="sm" c="dimmed">
                {project.archived ? t('settings.unarchiveHelp') : t('settings.archiveHelp')}
              </Text>
            </div>
            <Button
              variant="default"
              leftSection={project.archived ? <IconArchiveOff size={16} /> : <IconArchive size={16} />}
              loading={archive.isPending}
              onClick={() => archive.mutate(!project.archived)}
            >
              {project.archived ? t('settings.unarchive') : t('settings.archive')}
            </Button>
          </Group>
          <Divider />
          <Group justify="space-between" wrap="nowrap">
            <div>
              <Text fw={500}>{t('settings.delete')}</Text>
              <Text size="sm" c="dimmed">
                {t('settings.deleteHelp')}
              </Text>
            </div>
            <Button color="red" variant="light" leftSection={<IconTrash size={16} />} onClick={deleteModal.open}>
              {t('settings.delete')}
            </Button>
          </Group>
        </Stack>
      </Paper>
      <ConfirmDeleteModal
        project={project}
        opened={deleting}
        onClose={deleteModal.close}
        pending={remove.isPending}
        onConfirm={() => remove.mutate()}
      />
    </>
  );
}

function ConfirmDeleteModal({
  project,
  opened,
  onClose,
  pending,
  onConfirm,
}: {
  project: ProjectResponse;
  opened: boolean;
  onClose: () => void;
  pending: boolean;
  onConfirm: () => void;
}) {
  const { t } = useTranslation();
  const [typed, setTyped] = useState('');
  const close = () => {
    setTyped('');
    onClose();
  };
  return (
    <Modal opened={opened} onClose={close} title={t('settings.deleteTitle', { name: project.name })}>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (typed === project.key) onConfirm();
        }}
      >
        <Stack>
          <Text size="sm">{t('settings.deleteWarning')}</Text>
          <TextInput
            label={t('settings.deleteTypeKey', { key: project.key })}
            value={typed}
            onChange={(e) => setTyped(e.currentTarget.value.toUpperCase())}
            data-autofocus
          />
          <Group justify="flex-end">
            <Button variant="default" onClick={close}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" color="red" disabled={typed !== project.key} loading={pending}>
              {t('settings.deleteConfirm')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
