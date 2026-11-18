// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { ActionIcon, Alert, Avatar, Badge, Box, Button, Group, Menu, Paper, SegmentedControl, Stack, Text, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconDots, IconMail, IconPencil, IconTrash } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { del, patch, post, seg } from '../api/client';
import { keys, useAttachments, useComments, useMailSender } from '../api/queries';
import type { CommentResponse } from '../api/types';
import { ApiError } from '../lib/apiError';
import { formatDateTime } from '../lib/format';
import { Markdown } from './Markdown';
import { MarkdownEditor } from './MarkdownEditor';
import { ErrorAlert, PageLoader } from './QueryState';
import { initials } from './UserLabel';

const MAX_LENGTH = 100_000;

/** Comments of an issue, oldest first, with a form to add one at the bottom. */
export function IssueComments({ issueKey, canAdd }: { issueKey: string; canAdd: boolean }) {
  const { t } = useTranslation();
  const comments = useComments(issueKey);
  const invalidate = useInvalidateComments(issueKey);
  const [draft, setDraft] = useState('');
  const attachments = useAttachments(issueKey).data;
  // Issues made from a mail: a comment can also be mailed to the sender. Internal is the default.
  const sender = useMailSender(issueKey).data;
  const [external, setExternal] = useState(false);
  const toSender = !!sender?.address && external;

  const add = useMutation({
    mutationFn: (body: string) =>
      post<CommentResponse>(`/api/v1/issues/${seg(issueKey)}/comments`, { body, external: toSender }),
    onSuccess: () => {
      setDraft('');
      setExternal(false);
      invalidate();
    },
    onError: (e) => notifications.show({ color: 'red', title: t('error.title'), message: e.message }),
  });

  if (comments.isPending) return <PageLoader />;
  if (comments.isError) return <ErrorAlert error={comments.error} />;

  return (
    <Stack>
      {comments.data.length === 0 && (
        <Text c="dimmed" size="sm">
          {t('comment.empty')}
        </Text>
      )}
      {comments.data.map((comment) => (
        <Comment key={comment.id} issueKey={issueKey} comment={comment} />
      ))}
      {canAdd && (
        <Stack gap="xs">
          {sender?.address && (
            <SegmentedControl
              size="xs"
              color={external ? 'orange' : undefined}
              value={external ? 'external' : 'internal'}
              onChange={(v) => setExternal(v === 'external')}
              data={[
                { value: 'internal', label: t('comment.internal') },
                { value: 'external', label: t('comment.toSender', { address: sender.address }) },
              ]}
              style={{ alignSelf: 'flex-start', maxWidth: '100%' }}
            />
          )}
          {toSender && (
            <Alert color={sender.canSend ? 'orange' : 'red'} p="xs" icon={<IconMail size={16} />}>
              <Text size="sm">
                {sender.canSend
                  ? t('comment.toSenderHelp', { address: sender.address })
                  : t('comment.mailNotConfigured')}
              </Text>
            </Alert>
          )}
          <MarkdownEditor
            placeholder={t('comment.placeholder')}
            aria-label={t('comment.add')}
            minRows={7}
            resizable
            maxLength={MAX_LENGTH}
            value={draft}
            onChange={setDraft}
            attachments={attachments}
            previewSize="sm"
            onKeyDown={(e) => {
              if (e.key === 'Enter' && (e.ctrlKey || e.metaKey) && draft.trim() && !(toSender && !sender.canSend))
                add.mutate(draft);
            }}
          />
          <Group justify="space-between">
            <Text size="xs" c="dimmed">
              {t('comment.shortcut')}
            </Text>
            <Button
              size="xs"
              color={toSender ? 'orange' : undefined}
              leftSection={toSender ? <IconMail size={14} /> : undefined}
              disabled={!draft.trim() || (toSender && !sender.canSend)}
              loading={add.isPending}
              onClick={() => add.mutate(draft)}
            >
              {toSender ? t('comment.send') : t('comment.add')}
            </Button>
          </Group>
        </Stack>
      )}
    </Stack>
  );
}

function useInvalidateComments(issueKey: string) {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: keys.comments(issueKey) });
    void queryClient.invalidateQueries({ queryKey: keys.watchers(issueKey) });
    void queryClient.invalidateQueries({ queryKey: keys.issue(issueKey), exact: true });
  };
}

function Comment({ issueKey, comment }: { issueKey: string; comment: CommentResponse }) {
  const { t, i18n } = useTranslation();
  const invalidate = useInvalidateComments(issueKey);
  const attachments = useAttachments(issueKey).data;
  const [editing, setEditing] = useState<string | null>(null);
  const path = `/api/v1/issues/${seg(issueKey)}/comments/${comment.id}`;

  const save = useMutation({
    mutationFn: (body: string) => patch<CommentResponse>(path, { body, version: comment.version }),
    onSuccess: () => {
      setEditing(null);
      invalidate();
    },
    onError: (e) => {
      const conflict = e instanceof ApiError && e.status === 409;
      notifications.show({
        color: conflict ? 'orange' : 'red',
        title: conflict ? t('issue.conflictTitle') : t('error.title'),
        message: conflict ? t('comment.conflict') : e.message,
      });
      if (conflict) invalidate();
    },
  });

  const remove = useMutation({
    mutationFn: () => del(path),
    onSuccess: invalidate,
    onError: (e) => notifications.show({ color: 'red', title: t('error.title'), message: e.message }),
  });

  const author = comment.author?.displayName ?? t('history.system');
  const edited = comment.updatedAt !== comment.createdAt;

  return (
    <Paper withBorder p="sm" radius="sm">
      <Group justify="space-between" wrap="nowrap" align="flex-start">
        <Group gap={8} wrap="nowrap">
          <Avatar size={26} radius="xl" color="blue">
            {initials(author)}
          </Avatar>
          <div>
            <Text size="sm" fw={500}>
              {author}
            </Text>
            <Text size="xs" c="dimmed" title={edited ? formatDateTime(comment.updatedAt, i18n.language) : undefined}>
              {formatDateTime(comment.createdAt, i18n.language)}
              {edited && ` · ${t('comment.edited')}`}
            </Text>
          </div>
          {comment.external && (
            <Tooltip label={t('comment.externalHelp')} multiline w={260}>
              <Badge size="sm" variant="light" color="orange" leftSection={<IconMail size={12} />}>
                {t('comment.external')}
              </Badge>
            </Tooltip>
          )}
        </Group>
        {(comment.canEdit || comment.canDelete) && editing === null && (
          <Menu position="bottom-end">
            <Menu.Target>
              <ActionIcon variant="subtle" color="gray" aria-label={t('common.more')}>
                <IconDots size={16} />
              </ActionIcon>
            </Menu.Target>
            <Menu.Dropdown>
              {comment.canEdit && (
                <Menu.Item leftSection={<IconPencil size={16} />} onClick={() => setEditing(comment.body)}>
                  {t('common.edit')}
                </Menu.Item>
              )}
              {comment.canDelete && (
                <Menu.Item
                  color="red"
                  leftSection={<IconTrash size={16} />}
                  onClick={() => {
                    if (window.confirm(t('comment.deleteConfirm'))) remove.mutate();
                  }}
                >
                  {t('common.delete')}
                </Menu.Item>
              )}
            </Menu.Dropdown>
          </Menu>
        )}
      </Group>
      {editing === null ? (
        <Box mt="xs">
          <Markdown text={comment.body} attachments={attachments} size="sm" />
        </Box>
      ) : (
        <Stack gap="xs" mt="xs">
          <MarkdownEditor
            minRows={7}
            resizable
            maxLength={MAX_LENGTH}
            value={editing}
            onChange={setEditing}
            aria-label={t('common.edit')}
            attachments={attachments}
            previewSize="sm"
            autoFocus
          />
          <Group justify="flex-end" gap="xs">
            <Button size="xs" variant="default" onClick={() => setEditing(null)}>
              {t('common.cancel')}
            </Button>
            <Button size="xs" disabled={!editing.trim()} loading={save.isPending} onClick={() => save.mutate(editing)}>
              {t('common.save')}
            </Button>
          </Group>
        </Stack>
      )}
    </Paper>
  );
}
