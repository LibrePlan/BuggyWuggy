// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Alert, Button, Group, Modal, Stack, TextInput, Textarea } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { post } from '../api/client';
import { keys } from '../api/queries';
import type { CreateProjectRequest, ProjectResponse } from '../api/types';
import { fieldErrorMap } from '../lib/apiError';
import { PROJECT_KEY_PATTERN, suggestProjectKey } from '../lib/projectKey';

export function CreateProjectModal({ opened, onClose }: { opened: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [keyEdited, setKeyEdited] = useState(false);
  const form = useForm({
    initialValues: { name: '', key: '', description: '' },
    validate: {
      name: (v) => (v.trim() ? null : t('validation.required')),
      key: (v) => (PROJECT_KEY_PATTERN.test(v) ? null : t('validation.projectKey')),
    },
  });

  const create = useMutation({
    mutationFn: (request: CreateProjectRequest) => post<ProjectResponse>('/api/v1/projects', request),
    onSuccess: (project) => {
      void queryClient.invalidateQueries({ queryKey: keys.allProjects });
      close();
      navigate(`/projects/${project.key}`);
    },
    onError: (e) => form.setErrors(fieldErrorMap(e)),
  });

  function close() {
    form.reset();
    create.reset();
    setKeyEdited(false);
    onClose();
  }

  return (
    <Modal opened={opened} onClose={close} title={t('projects.create')}>
      <form
        onSubmit={form.onSubmit((v) =>
          create.mutate({ name: v.name.trim(), key: v.key, description: v.description.trim() || undefined }),
        )}
      >
        <Stack>
          {create.isError && Object.keys(fieldErrorMap(create.error)).length === 0 && (
            <Alert color="red">{create.error.message}</Alert>
          )}
          <TextInput
            label={t('project.name')}
            required
            data-autofocus
            {...form.getInputProps('name')}
            onChange={(e) => {
              const name = e.currentTarget.value;
              form.setFieldValue('name', name);
              if (!keyEdited) form.setFieldValue('key', suggestProjectKey(name));
            }}
          />
          <TextInput
            label={t('project.key')}
            description={t('projects.keyHelp')}
            required
            maxLength={10}
            {...form.getInputProps('key')}
            onChange={(e) => {
              setKeyEdited(true);
              form.setFieldValue('key', e.currentTarget.value.toUpperCase());
            }}
          />
          <Textarea label={t('project.description')} autosize minRows={3} {...form.getInputProps('description')} />
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
