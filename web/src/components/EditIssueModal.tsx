// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Button, Group, Modal, Stack, TextInput } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { useIssueMutations } from '../api/issueMutations';
import { useAttachments } from '../api/queries';
import type { IssueResponse, UpdateIssueRequest } from '../api/types';
import { fieldErrorMap } from '../lib/apiError';
import { changedFields } from '../lib/format';
import { MarkdownEditor } from './MarkdownEditor';
import {
  ComponentsSelect,
  DueDateInput,
  LabelsInput,
  PrioritySelect,
  VersionsSelect,
  toIds,
  toValues,
} from './IssueFormFields';

interface Values {
  summary: string;
  description: string;
  priorityId: string | null;
  labels: string[];
  componentIds: string[];
  affectsVersionIds: string[];
  fixVersionIds: string[];
  dueDate: string;
}

function valuesOf(issue: IssueResponse): Values {
  return {
    summary: issue.summary,
    description: issue.description ?? '',
    priorityId: issue.priority ? String(issue.priority.id) : null,
    labels: issue.labels,
    componentIds: toValues(issue.components),
    affectsVersionIds: toValues(issue.affectsVersions),
    fixVersionIds: toValues(issue.fixVersions),
    dueDate: issue.dueDate ?? '',
  };
}

export function EditIssueModal({ issue, opened, onClose }: { issue: IssueResponse; opened: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  const { update } = useIssueMutations(issue);
  const attachments = useAttachments(issue.key).data;
  const form = useForm<Values>({
    initialValues: valuesOf(issue),
    validate: { summary: (v) => (v.trim() ? null : t('validation.required')) },
  });

  useEffect(() => {
    if (opened) {
      form.setValues(valuesOf(issue));
      form.resetDirty(valuesOf(issue));
    }
    // Only when the modal opens: later changes of the issue must not overwrite the user's input.
  }, [opened]);

  function submit(v: Values) {
    const before = valuesOf(issue);
    const after: Values = { ...v, summary: v.summary.trim() };
    const changed = changedFields(before, after);
    if (Object.keys(changed).length === 0) {
      onClose();
      return;
    }
    const request: Omit<UpdateIssueRequest, 'version'> = {};
    if (changed.summary !== undefined) request.summary = after.summary;
    if (changed.description !== undefined) request.description = after.description;
    if (changed.priorityId !== undefined && after.priorityId) request.priorityId = Number(after.priorityId);
    if (changed.labels !== undefined) request.labels = after.labels;
    if (changed.componentIds !== undefined) request.componentIds = toIds(after.componentIds);
    if (changed.affectsVersionIds !== undefined) request.affectsVersionIds = toIds(after.affectsVersionIds);
    if (changed.fixVersionIds !== undefined) request.fixVersionIds = toIds(after.fixVersionIds);
    if (changed.dueDate !== undefined) {
      if (after.dueDate) request.dueDate = after.dueDate;
      else request.clearDueDate = true;
    }
    update.mutate(request, {
      onSuccess: onClose,
      onError: (e) => form.setErrors(fieldErrorMap(e)),
    });
  }

  const projectKey = issue.project.key;
  return (
    <Modal opened={opened} onClose={onClose} title={t('issue.editTitle', { key: issue.key })} size="70rem">
      <form onSubmit={form.onSubmit(submit)}>
        <Stack>
          <TextInput label={t('issue.summary')} required maxLength={255} data-autofocus {...form.getInputProps('summary')} />
          <MarkdownEditor
            label={t('issue.description')}
            minRows={6}
            maxRows={24}
            attachments={attachments}
            {...form.getInputProps('description')}
          />
          <PrioritySelect value={form.values.priorityId} onChange={(v) => form.setFieldValue('priorityId', v)} />
          <LabelsInput value={form.values.labels} onChange={(v) => form.setFieldValue('labels', v)} />
          <ComponentsSelect
            projectKey={projectKey}
            value={form.values.componentIds}
            onChange={(v) => form.setFieldValue('componentIds', v)}
          />
          <VersionsSelect
            projectKey={projectKey}
            label={t('issue.affectsVersions')}
            value={form.values.affectsVersionIds}
            onChange={(v) => form.setFieldValue('affectsVersionIds', v)}
          />
          <VersionsSelect
            projectKey={projectKey}
            label={t('issue.fixVersions')}
            value={form.values.fixVersionIds}
            onChange={(v) => form.setFieldValue('fixVersionIds', v)}
          />
          <DueDateInput value={form.values.dueDate} onChange={(v) => form.setFieldValue('dueDate', v)} />
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={update.isPending}>
              {t('common.save')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
