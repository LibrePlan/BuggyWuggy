// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import {
  Alert,
  Badge,
  Button,
  Code,
  CopyButton,
  Group,
  Modal,
  Paper,
  Stack,
  Switch,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { useDisclosure } from '@mantine/hooks';
import { IconCheck, IconCopy, IconKey, IconTrash } from '@tabler/icons-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { del, get, post } from '../api/client';
import type { ApiTokenResponse, CreateApiTokenRequest, CreatedApiTokenResponse } from '../api/types';
import { fieldErrorMap } from '../lib/apiError';
import { formatDate, formatDateTime } from '../lib/format';
import { notifyError, notifySaved } from '../lib/notify';
import { HelpHint } from './HelpHint';

/**
 * Personal API tokens: one's own (with "New token"), or, for administrators, those of another
 * user (only removing). {@code disabledReason}: why no tokens can be made, e.g. the demo account.
 */
export function ApiTokens({ userId, disabledReason }: { userId?: number; disabledReason?: string }) {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const own = userId === undefined;
  const base = own ? '/api/v1/myself/tokens' : `/api/v1/admin/users/${userId}/tokens`;
  const queryKey = ['apiTokens', userId ?? 'myself'];
  const tokens = useQuery({ queryKey, queryFn: () => get<ApiTokenResponse[]>(base) });
  const [creating, createModal] = useDisclosure(false);
  const remove = useMutation({
    mutationFn: (token: ApiTokenResponse) => del(`${base}/${token.id}`),
    onSuccess: (_, token) => {
      notifySaved(t('tokens.removed', { name: token.name }));
      void queryClient.invalidateQueries({ queryKey });
    },
    onError: notifyError,
  });

  const list = tokens.data ?? [];
  return (
    <Stack>
      <Title order={4}>{t('tokens.title')}</Title>
      <Text size="sm" c="dimmed">
        {own ? t('tokens.help') : t('tokens.adminHelp')}
      </Text>
      {list.length === 0 && tokens.isSuccess && <Text size="sm">{t('tokens.none')}</Text>}
      {list.length > 0 && (
        <Paper withBorder>
          <Table>
            <Table.Tbody>
              {list.map((token) => (
                <Table.Tr key={token.id}>
                  <Table.Td>
                    <Group gap="xs">
                      <Text size="sm" fw={500}>
                        {token.name}
                      </Text>
                      {token.readOnly && (
                        <Badge size="xs" variant="light">
                          {t('tokens.readOnly')}
                        </Badge>
                      )}
                    </Group>
                    <Text size="xs" c="dimmed">
                      <Code>{token.prefix}…</Code> ·{' '}
                      {t('tokens.created', { date: formatDateTime(token.createdAt, i18n.language) })} ·{' '}
                      {token.expiresOn
                        ? t('tokens.until', { date: formatDate(token.expiresOn, i18n.language) })
                        : t('tokens.noExpiry')}{' '}
                      ·{' '}
                      {token.lastUsedAt
                        ? t('tokens.lastUsed', { date: formatDateTime(token.lastUsedAt, i18n.language) })
                        : t('tokens.neverUsed')}
                    </Text>
                  </Table.Td>
                  <Table.Td w={110} ta="right">
                    <Button
                      variant="default"
                      size="xs"
                      color="red"
                      leftSection={<IconTrash size={14} />}
                      loading={remove.isPending && remove.variables?.id === token.id}
                      onClick={() => remove.mutate(token)}
                    >
                      {t('tokens.remove')}
                    </Button>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Paper>
      )}
      {own && (
        <Group gap="xs">
          <Button
            variant="default"
            leftSection={<IconKey size={16} />}
            disabled={!!disabledReason}
            onClick={createModal.open}
          >
            {t('tokens.new')}
          </Button>
          <HelpHint anchor="api-tokens" label={disabledReason ?? t('tokens.hint')} />
        </Group>
      )}
      {own && (
        <CreateTokenModal
          opened={creating}
          onClose={createModal.close}
          onCreated={() => void queryClient.invalidateQueries({ queryKey })}
        />
      )}
    </Stack>
  );
}

function CreateTokenModal({ opened, onClose, onCreated }: { opened: boolean; onClose: () => void; onCreated: () => void }) {
  const { t } = useTranslation();
  const [created, setCreated] = useState<CreatedApiTokenResponse | null>(null);
  const form = useForm({
    initialValues: { name: '', readOnly: true, expiresOn: '' },
    validate: { name: (v) => (v.trim() ? null : t('validation.required')) },
  });
  const create = useMutation({
    mutationFn: (request: CreateApiTokenRequest) => post<CreatedApiTokenResponse>('/api/v1/myself/tokens', request),
    onSuccess: (result) => {
      setCreated(result);
      onCreated();
    },
    onError: (e) => {
      const errors = fieldErrorMap(e);
      if (Object.keys(errors).length) form.setErrors(errors);
      else notifyError(e);
    },
  });
  function close() {
    setCreated(null);
    form.reset();
    onClose();
  }

  return (
    <Modal opened={opened} onClose={close} title={t('tokens.new')} size="lg">
      {created ? (
        <Stack>
          <Alert color="green" title={t('tokens.createdTitle', { name: created.token.name })}>
            {t('tokens.copyNow')}
          </Alert>
          <Group gap="xs" wrap="nowrap">
            <Code block style={{ flex: 1, wordBreak: 'break-all', whiteSpace: 'pre-wrap' }}>
              {created.secret}
            </Code>
            <CopyButton value={created.secret}>
              {({ copied, copy }) => (
                <Button
                  variant="default"
                  leftSection={copied ? <IconCheck size={16} /> : <IconCopy size={16} />}
                  onClick={copy}
                >
                  {copied ? t('tokens.copied') : t('tokens.copy')}
                </Button>
              )}
            </CopyButton>
          </Group>
          <Text size="sm" c="dimmed">
            {t('tokens.usage')}
          </Text>
          <Code block>{`curl -H "Authorization: Bearer ${created.token.prefix}…" ${window.location.origin}/api/v1/myself`}</Code>
          <Group justify="flex-end">
            <Button onClick={close}>{t('tokens.done')}</Button>
          </Group>
        </Stack>
      ) : (
        <form
          onSubmit={form.onSubmit((v) =>
            create.mutate({ name: v.name.trim(), readOnly: v.readOnly, expiresOn: v.expiresOn || undefined }),
          )}
        >
          <Stack>
            <TextInput
              label={t('tokens.name')}
              description={t('tokens.nameHelp')}
              required
              maxLength={100}
              data-autofocus
              {...form.getInputProps('name')}
            />
            <Switch
              label={t('tokens.readOnlyLabel')}
              description={t('tokens.readOnlyHelp')}
              {...form.getInputProps('readOnly', { type: 'checkbox' })}
            />
            <TextInput
              type="date"
              label={t('tokens.expiresOn')}
              description={t('tokens.expiresOnHelp')}
              min={new Date().toISOString().slice(0, 10)}
              {...form.getInputProps('expiresOn')}
            />
            <Group justify="flex-end">
              <Button variant="default" onClick={close}>
                {t('common.cancel')}
              </Button>
              <Button type="submit" loading={create.isPending}>
                {t('tokens.create')}
              </Button>
            </Group>
          </Stack>
        </form>
      )}
    </Modal>
  );
}
