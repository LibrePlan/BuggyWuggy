// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { ActionIcon, Anchor, Button, Group, Modal, Select, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';
import { IconLink, IconX } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { del, post, seg } from '../api/client';
import { keys, useLinks, useLinkTypes } from '../api/queries';
import type { IssueLinkResponse } from '../api/types';
import { ApiError } from '../lib/apiError';
import { groupByDescription, linkOptions, normalizeIssueKey } from '../lib/links';
import { IssueTypeIcon, StatusBadge } from './IssueIcons';

function useInvalidateLinks(issueKey: string) {
  const queryClient = useQueryClient();
  return (otherKey?: string) => {
    void queryClient.invalidateQueries({ queryKey: keys.links(issueKey) });
    void queryClient.invalidateQueries({ queryKey: keys.history(issueKey) });
    void queryClient.invalidateQueries({ queryKey: keys.issue(issueKey), exact: true });
    if (otherKey) void queryClient.invalidateQueries({ queryKey: ['issue', otherKey] });
  };
}

/** The "Linked issues" section; hidden when there are none and the user cannot add any. */
export function IssueLinks({ issueKey, canLink }: { issueKey: string; canLink: boolean }) {
  const { t } = useTranslation();
  const links = useLinks(issueKey);
  const invalidate = useInvalidateLinks(issueKey);
  const [adding, addModal] = useDisclosure(false);

  const remove = useMutation({
    mutationFn: (link: IssueLinkResponse) => del(`/api/v1/issues/${seg(issueKey)}/links/${link.id}`),
    onSuccess: (_, link) => invalidate(link.issue.key),
    onError: (e) => notifications.show({ color: 'red', title: t('error.title'), message: e.message }),
  });

  const items = links.data ?? [];
  if (items.length === 0 && !canLink) return null;

  return (
    <Stack gap="xs">
      <Group justify="space-between">
        <Title order={4}>{t('link.title')}</Title>
        {canLink && (
          <Button size="xs" variant="subtle" leftSection={<IconLink size={14} />} onClick={addModal.open}>
            {t('link.add')}
          </Button>
        )}
      </Group>
      {items.length === 0 ? (
        <Text c="dimmed" size="sm">
          {t('link.empty')}
        </Text>
      ) : (
        <Table verticalSpacing={6}>
          <Table.Tbody>
            {groupByDescription(items).map(([description, group]) =>
              group.map((link, index) => (
                <Table.Tr key={link.id}>
                  <Table.Td w={130}>
                    {index === 0 && (
                      <Text size="sm" c="dimmed">
                        {description}
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td w={30}>
                    <IssueTypeIcon type={link.issue.issueType} size={16} />
                  </Table.Td>
                  <Table.Td w={100}>
                    <Anchor component={Link} to={`/browse/${link.issue.key}`} size="sm">
                      {link.issue.key}
                    </Anchor>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm" lineClamp={1}>
                      {link.issue.summary}
                    </Text>
                  </Table.Td>
                  <Table.Td w={130}>
                    <StatusBadge status={link.issue.status} size="xs" />
                  </Table.Td>
                  {canLink && (
                    <Table.Td w={36}>
                      <ActionIcon
                        variant="subtle"
                        color="gray"
                        size="sm"
                        aria-label={t('link.remove', { key: link.issue.key })}
                        title={t('link.remove', { key: link.issue.key })}
                        loading={remove.isPending && remove.variables?.id === link.id}
                        onClick={() => remove.mutate(link)}
                      >
                        <IconX size={14} />
                      </ActionIcon>
                    </Table.Td>
                  )}
                </Table.Tr>
              )),
            )}
          </Table.Tbody>
        </Table>
      )}
      <AddLinkModal issueKey={issueKey} opened={adding} onClose={addModal.close} />
    </Stack>
  );
}

function AddLinkModal({ issueKey, opened, onClose }: { issueKey: string; opened: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  const types = useLinkTypes().data;
  const options = useMemo(() => linkOptions(types ?? []), [types]);
  const invalidate = useInvalidateLinks(issueKey);
  const [choice, setChoice] = useState<string | null>(null);
  const [other, setOther] = useState('');
  const [error, setError] = useState<string | null>(null);
  const selected = options.find((o) => o.value === (choice ?? options[0]?.value));
  const otherKey = normalizeIssueKey(other);

  const add = useMutation({
    mutationFn: () =>
      post<IssueLinkResponse>(`/api/v1/issues/${seg(issueKey)}/links`, {
        linkTypeId: selected!.linkTypeId,
        direction: selected!.direction,
        issue: otherKey,
      }),
    onSuccess: (link) => {
      invalidate(link.issue.key);
      close();
    },
    onError: (e) => setError(e instanceof ApiError ? e.message : String(e)),
  });

  function close() {
    setOther('');
    setError(null);
    add.reset();
    onClose();
  }

  return (
    <Modal opened={opened} onClose={close} title={t('link.addTitle', { key: issueKey })}>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          if (selected && otherKey) add.mutate();
        }}
      >
        <Stack>
          <Select
            label={t('link.thisIssue')}
            data={options.map((o) => ({ value: o.value, label: o.label }))}
            value={selected?.value ?? null}
            onChange={setChoice}
            allowDeselect={false}
          />
          <TextInput
            label={t('link.otherIssue')}
            placeholder="BUG-123"
            value={other}
            data-autofocus
            onChange={(e) => {
              setOther(e.currentTarget.value);
              setError(null);
            }}
            error={error ?? (other.trim() && !otherKey ? t('link.keyInvalid') : null)}
          />
          <Group justify="flex-end">
            <Button variant="default" onClick={close}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" disabled={!selected || !otherKey} loading={add.isPending}>
              {t('link.submit')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
