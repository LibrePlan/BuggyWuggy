// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Anchor, Button, Group, Modal, Radio, Select, Stack, Text, TextInput, Textarea } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { post, seg } from '../api/client';
import { keys, useIssue, useIssueTypes, useProjects } from '../api/queries';
import type { CommentResponse, IssueResponse } from '../api/types';
import { notifyError } from '../lib/notify';

/** Markdown escapes back to the characters, for a summary. */
function plain(markdown: string): string {
  return markdown.replace(/\\([\\*_`[\]~<>|#])/g, '$1');
}

/** The first sentence or line of an answer, as a suggested summary. */
function suggestSummary(body: string): string {
  const first = plain(body).split('\n').find((l) => l.trim() && !l.startsWith('*Attached')) ?? '';
  const sentence = (first.split(/(?<=[.!?])\s/)[0] ?? '').trim();
  return sentence.length > 120 ? `${sentence.slice(0, 119)}…` : sentence;
}

/**
 * Makes a new issue of an answer that came in by e-mail: as a copy (the answer stays) or moved
 * (it leaves this issue; only with the right to delete all comments). The issues get a Split link.
 */
export function SplitAnswerModal({
  issueKey,
  comment,
  onClose,
}: {
  issueKey: string;
  comment: CommentResponse;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const issue = useIssue(issueKey).data;
  const types = (useIssueTypes().data ?? []).filter((type) => type.hierarchyLevel === 0);
  const projects = useProjects('', 0).data?.items ?? [];
  const [summary, setSummary] = useState(() => suggestSummary(comment.body));
  const [description, setDescription] = useState(() => comment.body);
  const [project, setProject] = useState<string | null>(null);
  const [typeId, setTypeId] = useState<string | null>(null);
  const [mode, setMode] = useState<'copy' | 'move'>('copy');
  const projectKey = project ?? issue?.project.key ?? null;
  const type = typeId ?? (issue ? String(issue.issueType.id) : null);

  const split = useMutation({
    mutationFn: () =>
      post<IssueResponse>(`/api/v1/issues/${seg(issueKey)}/comments/${comment.id}/split`, {
        summary,
        description,
        project: projectKey,
        issueTypeId: type ? Number(type) : null,
        move: mode === 'move',
      }),
    onSuccess: (created) => {
      for (const key of [keys.comments(issueKey), keys.links(issueKey), keys.attachments(issueKey)]) {
        void queryClient.invalidateQueries({ queryKey: key });
      }
      notifications.show({
        color: 'green',
        title: t('comment.split.done', { key: created.key }),
        // Notifications are rendered outside the router: a plain link, not <Link>.
        message: (
          <Anchor href={`/browse/${created.key}`} size="sm">
            {t('comment.split.open', { key: created.key })}
          </Anchor>
        ),
      });
      onClose();
    },
    onError: notifyError,
  });

  return (
    <Modal opened onClose={onClose} title={t('comment.split.title')} size="lg">
      <Stack>
        <Text size="sm" c="dimmed">
          {t('comment.split.intro')}
        </Text>
        <TextInput
          label={t('comment.split.summary')}
          required
          maxLength={255}
          value={summary}
          onChange={(e) => setSummary(e.currentTarget.value)}
          data-autofocus
        />
        <Group grow align="flex-start">
          <Select
            label={t('comment.split.project')}
            searchable
            data={projects.map((p) => ({ value: p.key, label: `${p.key} — ${p.name}` }))}
            value={projectKey}
            onChange={setProject}
            allowDeselect={false}
          />
          <Select
            label={t('comment.split.type')}
            data={types.map((it) => ({ value: String(it.id), label: it.name }))}
            value={type}
            onChange={setTypeId}
            allowDeselect={false}
          />
        </Group>
        <Textarea
          label={t('comment.split.description')}
          autosize
          minRows={4}
          maxRows={12}
          maxLength={100_000}
          value={description}
          onChange={(e) => setDescription(e.currentTarget.value)}
        />
        <Radio.Group
          label={t('comment.split.mode')}
          value={mode}
          onChange={(v) => setMode(v as 'copy' | 'move')}
        >
          <Stack gap="xs" mt={6}>
            <Radio value="copy" label={t('comment.split.copy')} description={t('comment.split.copyHelp')} />
            <Radio
              value="move"
              disabled={!comment.canDelete}
              label={t('comment.split.move')}
              description={comment.canDelete ? t('comment.split.moveHelp') : t('comment.split.moveNotAllowed')}
            />
          </Stack>
        </Radio.Group>
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            {t('common.cancel')}
          </Button>
          <Button
            loading={split.isPending}
            disabled={!summary.trim() || !projectKey || !type}
            onClick={() => split.mutate()}
          >
            {t('comment.split.submit')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
