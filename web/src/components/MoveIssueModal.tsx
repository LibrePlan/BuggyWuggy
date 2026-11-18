// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Alert, Button, Group, List, Loader, Modal, Select, Stack, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconAlertTriangle } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router';
import { post, seg } from '../api/client';
import { keys, useMovePreview } from '../api/queries';
import type { IssueResponse, MoveOptions } from '../api/types';
import { notifyError } from '../lib/notify';

/**
 * Moves an issue to another project. The dialog shows what changes on the way (components and
 * versions without a namesake there, an assignee who cannot be assigned there, the epic) and
 * that a closed stub with the old key stays behind.
 */
export function MoveIssueModal({
  issue,
  options,
  opened,
  onClose,
}: {
  issue: IssueResponse;
  options: MoveOptions;
  opened: boolean;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [project, setProject] = useState<string | null>(null);
  const preview = useMovePreview(issue.key, opened ? project : null);
  const p = preview.data;
  const losses = p
    ? [
        ...p.droppedComponents.map((name) => t('issue.move.dropComponent', { name })),
        ...p.droppedAffectsVersions.map((name) => t('issue.move.dropAffectsVersion', { name })),
        ...p.droppedFixVersions.map((name) => t('issue.move.dropFixVersion', { name })),
        ...(p.removedAssignee ? [t('issue.move.unassign', { name: p.removedAssignee.displayName })] : []),
        ...(p.leavesEpic ? [t('issue.move.leaveEpic', { key: p.leavesEpic.key })] : []),
      ]
    : [];

  const move = useMutation({
    mutationFn: () =>
      post<IssueResponse>(`/api/v1/issues/${seg(issue.key)}/move`, { project, version: issue.version }),
    onSuccess: (moved) => {
      // The old key is now the stub; everything about the issue lives under its new key.
      queryClient.removeQueries({ queryKey: keys.issue(issue.key) });
      void queryClient.invalidateQueries({ queryKey: ['project'] });
      void queryClient.invalidateQueries({ queryKey: keys.allSearches });
      notifications.show({ color: 'green', message: t('issue.move.done', { from: issue.key, to: moved.key }) });
      onClose();
      navigate(`/browse/${moved.key}`);
    },
    onError: notifyError,
  });

  function close() {
    setProject(null);
    onClose();
  }

  return (
    <Modal opened={opened} onClose={close} title={t('issue.move.title', { key: issue.key })}>
      <Stack>
        <Text size="sm">{t('issue.move.intro', { key: issue.key })}</Text>
        <Select
          label={t('issue.move.project')}
          placeholder={t('issue.move.projectPlaceholder')}
          searchable
          data={options.targets.map((target) => ({ value: target.key, label: `${target.key} — ${target.name}` }))}
          value={project}
          onChange={setProject}
          data-autofocus
        />
        {project && preview.isPending && <Loader size="sm" />}
        {project && preview.isError && (
          <Alert color="red" icon={<IconAlertTriangle size={16} />}>
            {preview.error.message}
          </Alert>
        )}
        {p && losses.length > 0 && (
          <Alert color="yellow" icon={<IconAlertTriangle size={16} />} title={t('issue.move.changes')}>
            <List size="sm">
              {losses.map((loss) => (
                <List.Item key={loss}>{loss}</List.Item>
              ))}
            </List>
          </Alert>
        )}
        {p?.mailboxFrom && (
          <Text size="sm" c="dimmed">
            {p.mailboxTo !== p.mailboxFrom
              ? t('issue.move.mailboxChanges', { from: p.mailboxFrom, to: p.mailboxTo })
              : t('issue.move.mailboxStays', { mailbox: p.mailboxFrom })}
          </Text>
        )}
        {p && losses.length === 0 && (
          <Text size="sm" c="dimmed">
            {t('issue.move.noChanges')}
          </Text>
        )}
        <Group justify="flex-end">
          <Button variant="default" onClick={close}>
            {t('common.cancel')}
          </Button>
          <Button loading={move.isPending} disabled={!p} onClick={() => move.mutate()}>
            {t('issue.move.submit')}
          </Button>
        </Group>
      </Stack>
    </Modal>
  );
}
