// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Alert, Button, Group, Modal, Stack, TextInput, Textarea } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { post } from '../api/client';
import { keys, useIssueTypes, usePriorities, useProjectPermissions } from '../api/queries';
import type { CreateIssueRequest, IssueResponse, UserRef } from '../api/types';
import { fieldErrorMap } from '../lib/apiError';
import { childTypes, topLevelTypes } from '../lib/issueTypes';
import {
  ComponentsSelect,
  DueDateInput,
  IssueTypeSelect,
  LabelsInput,
  PrioritySelect,
  VersionsSelect,
  toIds,
} from './IssueFormFields';
import { UserPicker } from './UserPicker';

interface Props {
  projectKey: string;
  /** When given, the new issue becomes a child of this issue (a sub-task, or an issue in an epic). */
  parent?: { key: string; hierarchyLevel: number };
  opened: boolean;
  onClose: () => void;
  onCreated?: (issue: IssueResponse) => void;
}

interface Values {
  issueTypeId: string | null;
  summary: string;
  description: string;
  priorityId: string | null;
  assignee: UserRef | null;
  labels: string[];
  componentIds: string[];
  fixVersionIds: string[];
  dueDate: string;
}

export function CreateIssueModal({ projectKey, parent, opened, onClose, onCreated }: Props) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const allTypes = useIssueTypes().data;
  const priorities = usePriorities().data;
  const permissions = useProjectPermissions(projectKey).data;
  const canAssign = permissions?.has('ASSIGN_ISSUE') ?? false;

  const types = useMemo(
    () => (allTypes ? (parent ? childTypes(allTypes, parent.hierarchyLevel) : topLevelTypes(allTypes)) : []),
    [allTypes, parent],
  );

  const form = useForm<Values>({
    initialValues: {
      issueTypeId: null,
      summary: '',
      description: '',
      priorityId: null,
      assignee: null,
      labels: [],
      componentIds: [],
      fixVersionIds: [],
      dueDate: '',
    },
    validate: {
      issueTypeId: (v) => (v ? null : t('validation.required')),
      summary: (v) => (v.trim() ? null : t('validation.required')),
    },
  });

  // Defaults once the lookups are there: a "Task"-like type and the default priority.
  useEffect(() => {
    if (!opened) return;
    if (!form.values.issueTypeId && types.length > 0) {
      const preferred = types.find((type) => type.icon === 'task') ?? types[0]!;
      form.setFieldValue('issueTypeId', String(preferred.id));
    }
    if (!form.values.priorityId && priorities) {
      const preferred = priorities.find((p) => p.isDefault);
      if (preferred) form.setFieldValue('priorityId', String(preferred.id));
    }
  }, [opened, types, priorities]);

  const create = useMutation({
    mutationFn: (request: CreateIssueRequest) => post<IssueResponse>('/api/v1/issues', request),
    onSuccess: (issue) => {
      void queryClient.invalidateQueries({ queryKey: keys.project(projectKey) });
      if (parent) void queryClient.invalidateQueries({ queryKey: keys.issue(parent.key) });
      close();
      onCreated?.(issue);
    },
    onError: (e) => form.setErrors(fieldErrorMap(e)),
  });

  function close() {
    form.reset();
    create.reset();
    onClose();
  }

  function submit(v: Values) {
    create.mutate({
      project: projectKey,
      parent: parent?.key,
      issueTypeId: Number(v.issueTypeId),
      summary: v.summary.trim(),
      description: v.description.trim() || undefined,
      priorityId: v.priorityId ? Number(v.priorityId) : undefined,
      assigneeId: v.assignee?.id,
      labels: v.labels,
      componentIds: toIds(v.componentIds),
      fixVersionIds: toIds(v.fixVersionIds),
      dueDate: v.dueDate || undefined,
    });
  }

  const title = parent ? t('issue.createChild', { parent: parent.key }) : t('issue.create');

  return (
    <Modal opened={opened} onClose={close} title={title} size="lg">
      <form onSubmit={form.onSubmit(submit)}>
        <Stack>
          {create.isError && Object.keys(fieldErrorMap(create.error)).length === 0 && (
            <Alert color="red">{create.error.message}</Alert>
          )}
          <IssueTypeSelect
            types={types}
            value={form.values.issueTypeId}
            onChange={(v) => form.setFieldValue('issueTypeId', v)}
            error={form.errors.issueTypeId}
          />
          <TextInput label={t('issue.summary')} required data-autofocus maxLength={255} {...form.getInputProps('summary')} />
          <Textarea
            label={t('issue.description')}
            autosize
            minRows={4}
            maxRows={16}
            {...form.getInputProps('description')}
          />
          <PrioritySelect value={form.values.priorityId} onChange={(v) => form.setFieldValue('priorityId', v)} />
          {canAssign && (
            <UserPicker
              label={t('issue.assignee')}
              projectKey={projectKey}
              value={form.values.assignee}
              onChange={(u) => form.setFieldValue('assignee', u)}
            />
          )}
          <LabelsInput value={form.values.labels} onChange={(v) => form.setFieldValue('labels', v)} />
          <ComponentsSelect
            projectKey={projectKey}
            value={form.values.componentIds}
            onChange={(v) => form.setFieldValue('componentIds', v)}
          />
          <VersionsSelect
            projectKey={projectKey}
            label={t('issue.fixVersions')}
            value={form.values.fixVersionIds}
            onChange={(v) => form.setFieldValue('fixVersionIds', v)}
          />
          <DueDateInput value={form.values.dueDate} onChange={(v) => form.setFieldValue('dueDate', v)} />
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
