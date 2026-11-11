// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Alert, Button, Group, Modal, Select, Stack, TextInput, Textarea } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { post } from '../api/client';
import { keys, useAllProjects } from '../api/queries';
import type { CreateFilterRequest, FilterResponse, SharedWith } from '../api/types';
import { fieldErrorMap } from '../lib/apiError';
import { normalizeQuery, type IssueQuery } from '../lib/issueQuery';

interface Values {
  name: string;
  description: string;
  sharedWith: SharedWith;
  projectId: string | null;
}

/** Saves the current search as a new filter. */
export function SaveFilterModal({
  query,
  opened,
  onClose,
  onSaved,
}: {
  query: IssueQuery;
  opened: boolean;
  onClose: () => void;
  onSaved: (filter: FilterResponse) => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const projects = useAllProjects().data ?? [];
  const form = useForm<Values>({
    initialValues: { name: '', description: '', sharedWith: 'PRIVATE', projectId: null },
    validate: {
      name: (v) => (v.trim() ? null : t('validation.required')),
      projectId: (v, values) => (values.sharedWith === 'PROJECT' && !v ? t('validation.required') : null),
    },
  });

  const save = useMutation({
    mutationFn: (request: CreateFilterRequest) => post<FilterResponse>('/api/v1/filters', request),
    onSuccess: (filter) => {
      void queryClient.invalidateQueries({ queryKey: keys.filters });
      close();
      onSaved(filter);
    },
    onError: (e) => form.setErrors(fieldErrorMap(e)),
  });

  function close() {
    form.reset();
    save.reset();
    onClose();
  }

  return (
    <Modal opened={opened} onClose={close} title={t('filter.saveTitle')}>
      <form
        onSubmit={form.onSubmit((v) =>
          save.mutate({
            name: v.name.trim(),
            description: v.description.trim() || undefined,
            query: normalizeQuery(query),
            sharedWith: v.sharedWith,
            projectId: v.sharedWith === 'PROJECT' && v.projectId ? Number(v.projectId) : undefined,
          }),
        )}
      >
        <Stack>
          {save.isError && Object.keys(fieldErrorMap(save.error)).length === 0 && (
            <Alert color="red">{save.error.message}</Alert>
          )}
          <TextInput label={t('filter.name')} required maxLength={255} data-autofocus {...form.getInputProps('name')} />
          <Textarea label={t('project.description')} autosize minRows={2} {...form.getInputProps('description')} />
          <ShareSelect value={form.values.sharedWith} onChange={(v) => form.setFieldValue('sharedWith', v)} />
          {form.values.sharedWith === 'PROJECT' && (
            <Select
              label={t('issue.project')}
              required
              searchable
              data={projects.map((p) => ({ value: String(p.id), label: p.name }))}
              {...form.getInputProps('projectId')}
            />
          )}
          <Group justify="flex-end">
            <Button variant="default" onClick={close}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={save.isPending}>
              {t('common.save')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}

export function ShareSelect({ value, onChange }: { value: SharedWith; onChange: (value: SharedWith) => void }) {
  const { t } = useTranslation();
  return (
    <Select
      label={t('filter.sharedWith')}
      data={(['PRIVATE', 'LOGGED_IN', 'PROJECT'] as const).map((v) => ({ value: v, label: t(`filter.share.${v}`) }))}
      value={value}
      onChange={(v) => v && onChange(v as SharedWith)}
      allowDeselect={false}
    />
  );
}
