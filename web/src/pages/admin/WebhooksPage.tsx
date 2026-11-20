// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import {
  Accordion,
  Alert,
  Anchor,
  Badge,
  Breadcrumbs,
  Button,
  Checkbox,
  Code,
  CopyButton,
  Group,
  Modal,
  MultiSelect,
  Paper,
  Radio,
  Stack,
  Switch,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { IconCheck, IconCopy, IconPlus, IconRefresh, IconSend, IconTrash } from '@tabler/icons-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router';
import { del, get, post, put } from '../../api/client';
import { useProjects } from '../../api/queries';
import type {
  DeliveryResponse,
  WebhookRequest,
  WebhookResponse,
  WebhookSecretResponse,
} from '../../api/types';
import { ErrorAlert, isNotFound, PageLoader } from '../../components/QueryState';
import { fieldErrorMap } from '../../lib/apiError';
import { formatDateTime } from '../../lib/format';
import { notifyError, notifySaved } from '../../lib/notify';
import { NotFoundPage } from '../NotFoundPage';

const EVENTS = ['issue.created', 'issue.changed', 'issue.commented', 'issue.deleted'] as const;
const webhooksKey = ['admin', 'webhooks'] as const;

function statusColor(status: string) {
  return status === 'SENT' ? 'green' : status === 'FAILED' ? 'red' : 'yellow';
}

/** Administration → Webhooks: the list. */
export function WebhooksPage() {
  const { t, i18n } = useTranslation();
  const webhooks = useQuery({ queryKey: webhooksKey, queryFn: () => get<WebhookResponse[]>('/api/v1/admin/webhooks') });
  if (webhooks.isPending) return <PageLoader />;
  if (webhooks.isError) return <ErrorAlert error={webhooks.error} />;
  return (
    <Stack>
      <Text size="sm" c="dimmed">
        {t('webhooks.intro')}
      </Text>
      <Group>
        <Button component={Link} to="/admin/webhooks/new" leftSection={<IconPlus size={16} />}>
          {t('webhooks.new')}
        </Button>
      </Group>
      {webhooks.data.length === 0 ? (
        <Text size="sm">{t('webhooks.none')}</Text>
      ) : (
        <Paper withBorder>
          <Table>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('webhooks.name')}</Table.Th>
                <Table.Th visibleFrom="sm">{t('webhooks.url')}</Table.Th>
                <Table.Th>{t('webhooks.lastDelivery')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {webhooks.data.map((w) => (
                <Table.Tr key={w.id}>
                  <Table.Td>
                    <Group gap="xs">
                      <Anchor component={Link} to={`/admin/webhooks/${w.id}`} size="sm">
                        {w.name}
                      </Anchor>
                      {!w.active && (
                        <Badge size="xs" color="gray" variant="light">
                          {t('webhooks.inactive')}
                        </Badge>
                      )}
                      {w.failed > 0 && (
                        <Badge size="xs" color="red" variant="light">
                          {t('webhooks.failedCount', { count: w.failed })}
                        </Badge>
                      )}
                      {w.pending > 0 && (
                        <Badge size="xs" color="yellow" variant="light">
                          {t('webhooks.pendingCount', { count: w.pending })}
                        </Badge>
                      )}
                    </Group>
                  </Table.Td>
                  <Table.Td visibleFrom="sm">
                    <Text size="sm" style={{ wordBreak: 'break-all' }}>
                      {w.url}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    {w.lastDelivery ? (
                      <Group gap="xs" wrap="nowrap">
                        <Badge size="xs" color={statusColor(w.lastDelivery.status)} variant="light">
                          {t(`webhooks.status.${w.lastDelivery.status}`)}
                        </Badge>
                        <Text size="xs" c="dimmed">
                          {formatDateTime(w.lastDelivery.at, i18n.language)}
                        </Text>
                      </Group>
                    ) : (
                      <Text size="xs" c="dimmed">
                        {t('webhooks.never')}
                      </Text>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Paper>
      )}
    </Stack>
  );
}

/** Administration → Webhooks → one webhook (or a new one: no :webhookId). */
export function WebhookPage() {
  const { t } = useTranslation();
  const param = useParams().webhookId;
  const id = param ? Number(param) : undefined;
  const webhook = useQuery({
    queryKey: [...webhooksKey, id],
    queryFn: () => get<WebhookResponse>(`/api/v1/admin/webhooks/${id}`),
    enabled: id !== undefined,
  });
  const [secret, setSecret] = useState<string | null>(null);

  if (id !== undefined && webhook.isPending) return <PageLoader />;
  if (webhook.isError) return isNotFound(webhook.error) ? <NotFoundPage /> : <ErrorAlert error={webhook.error} />;
  return (
    <Stack>
      <Breadcrumbs>
        <Anchor component={Link} to="/admin/webhooks" size="sm">
          {t('admin.webhooks')}
        </Anchor>
        <Text size="sm">{webhook.data?.name ?? t('webhooks.new')}</Text>
      </Breadcrumbs>
      <WebhookForm key={webhook.data?.id ?? 'new'} webhook={webhook.data} onSecret={setSecret} />
      {webhook.data && <Deliveries webhook={webhook.data} onSecret={setSecret} />}
      <SecretModal secret={secret} onClose={() => setSecret(null)} />
    </Stack>
  );
}

function WebhookForm({ webhook, onSecret }: { webhook?: WebhookResponse; onSecret: (s: string) => void }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [search, setSearch] = useState('');
  const projects = useProjects(search, 0);
  const form = useForm({
    initialValues: {
      name: webhook?.name ?? '',
      url: webhook?.url ?? '',
      events: webhook?.events ?? [...EVENTS],
      allProjects: webhook ? webhook.allProjects : true,
      projectKeys: webhook?.projectKeys ?? [],
      active: webhook?.active ?? true,
    },
    validate: {
      name: (v) => (v.trim() ? null : t('validation.required')),
      url: (v) => (/^https?:\/\/\S+$/i.test(v.trim()) ? null : t('webhooks.urlInvalid')),
      events: (v) => (v.length ? null : t('webhooks.eventsRequired')),
      projectKeys: (v, values) => (values.allProjects || v.length ? null : t('webhooks.projectsRequired')),
    },
  });
  const save = useMutation({
    mutationFn: (request: WebhookRequest): Promise<{ webhook: WebhookResponse; secret?: string }> =>
      webhook
        ? put<WebhookResponse>(`/api/v1/admin/webhooks/${webhook.id}`, request).then((w) => ({ webhook: w }))
        : post<WebhookSecretResponse>('/api/v1/admin/webhooks', request),
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: webhooksKey });
      notifySaved();
      if (result.secret) {
        onSecret(result.secret);
        void navigate(`/admin/webhooks/${result.webhook.id}`, { replace: true });
      }
    },
    onError: (e) => {
      const errors = fieldErrorMap(e);
      if (Object.keys(errors).length) form.setErrors(errors);
      else notifyError(e);
    },
  });
  const remove = useMutation({
    mutationFn: () => del(`/api/v1/admin/webhooks/${webhook!.id}`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: webhooksKey });
      notifySaved(t('webhooks.deleted', { name: webhook!.name }));
      void navigate('/admin/webhooks');
    },
    onError: notifyError,
  });
  const [confirmDelete, setConfirmDelete] = useState(false);

  const found = (projects.data?.items ?? []).map((p) => ({ value: p.key, label: `${p.key} · ${p.name}` }));
  const selected = form.values.projectKeys
    .filter((k) => !found.some((f) => f.value === k))
    .map((k) => ({ value: k, label: k }));

  return (
    <form
      onSubmit={form.onSubmit((v) =>
        save.mutate({
          name: v.name.trim(),
          url: v.url.trim(),
          events: v.events,
          allProjects: v.allProjects,
          projectKeys: v.allProjects ? [] : v.projectKeys,
          active: v.active,
        }),
      )}
    >
      <Stack maw={720}>
        <Title order={4}>{webhook ? t('webhooks.settings') : t('webhooks.new')}</Title>
        <TextInput label={t('webhooks.name')} required maxLength={100} {...form.getInputProps('name')} />
        <TextInput
          label={t('webhooks.url')}
          description={t('webhooks.urlHelp')}
          placeholder="https://example.org/buggywuggy-hook"
          required
          {...form.getInputProps('url')}
        />
        <Checkbox.Group label={t('webhooks.events')} {...form.getInputProps('events')}>
          <Stack gap={6} mt={6}>
            {EVENTS.map((e) => (
              <Checkbox key={e} value={e} label={t(`webhooks.event.${e}`)} description={<Code>{e}</Code>} />
            ))}
          </Stack>
        </Checkbox.Group>
        <Radio.Group
          label={t('webhooks.projects')}
          value={form.values.allProjects ? 'all' : 'some'}
          onChange={(v) => form.setFieldValue('allProjects', v === 'all')}
        >
          <Group mt={6}>
            <Radio value="all" label={t('webhooks.allProjects')} />
            <Radio value="some" label={t('webhooks.someProjects')} />
          </Group>
        </Radio.Group>
        {!form.values.allProjects && (
          <MultiSelect
            aria-label={t('webhooks.projects')}
            placeholder={t('webhooks.chooseProjects')}
            searchable
            searchValue={search}
            onSearchChange={setSearch}
            data={[...selected, ...found]}
            {...form.getInputProps('projectKeys')}
          />
        )}
        <Switch
          label={t('webhooks.active')}
          description={t('webhooks.activeHelp')}
          {...form.getInputProps('active', { type: 'checkbox' })}
        />
        <Group>
          <Button type="submit" loading={save.isPending}>
            {webhook ? t('common.save') : t('webhooks.create')}
          </Button>
          {webhook && (
            <Button
              variant="default"
              color="red"
              leftSection={<IconTrash size={16} />}
              onClick={() => setConfirmDelete(true)}
            >
              {t('webhooks.delete')}
            </Button>
          )}
        </Group>
      </Stack>
      <Modal opened={confirmDelete} onClose={() => setConfirmDelete(false)} title={t('webhooks.delete')}>
        <Stack>
          <Text size="sm">{t('webhooks.deleteConfirm', { name: webhook?.name })}</Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setConfirmDelete(false)}>
              {t('common.cancel')}
            </Button>
            <Button color="red" loading={remove.isPending} onClick={() => remove.mutate()}>
              {t('webhooks.delete')}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </form>
  );
}

function Deliveries({ webhook, onSecret }: { webhook: WebhookResponse; onSecret: (s: string) => void }) {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const key = [...webhooksKey, webhook.id, 'deliveries'];
  const deliveries = useQuery({
    queryKey: key,
    queryFn: () => get<DeliveryResponse[]>(`/api/v1/admin/webhooks/${webhook.id}/deliveries`),
    // Pending deliveries change by themselves.
    refetchInterval: (q) => (q.state.data?.some((d) => d.status === 'PENDING') ? 5000 : false),
  });
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: webhooksKey });
  };
  const test = useMutation({
    mutationFn: () => post<DeliveryResponse>(`/api/v1/admin/webhooks/${webhook.id}/test`),
    onSuccess: () => {
      notifySaved(t('webhooks.testQueued'));
      refresh();
    },
    onError: notifyError,
  });
  const retry = useMutation({
    mutationFn: (d: DeliveryResponse) =>
      post<DeliveryResponse>(`/api/v1/admin/webhooks/${webhook.id}/deliveries/${d.id}/retry`),
    onSuccess: refresh,
    onError: notifyError,
  });
  const rotate = useMutation({
    mutationFn: () => post<WebhookSecretResponse>(`/api/v1/admin/webhooks/${webhook.id}/secret`),
    onSuccess: (r) => {
      onSecret(r.secret);
      refresh();
    },
    onError: notifyError,
  });
  const [confirmRotate, setConfirmRotate] = useState(false);

  return (
    <Stack>
      <Title order={4}>{t('webhooks.deliveries')}</Title>
      <Text size="sm" c="dimmed">
        {t('webhooks.deliveriesHelp')}
      </Text>
      <Group>
        <Button
          variant="default"
          leftSection={<IconSend size={16} />}
          loading={test.isPending}
          disabled={!webhook.active}
          title={webhook.active ? undefined : t('webhooks.testInactive')}
          onClick={() => test.mutate()}
        >
          {t('webhooks.test')}
        </Button>
        <Button variant="default" leftSection={<IconRefresh size={16} />} onClick={() => setConfirmRotate(true)}>
          {t('webhooks.rotate')}
        </Button>
      </Group>
      {deliveries.data?.length === 0 && <Text size="sm">{t('webhooks.noDeliveries')}</Text>}
      {!!deliveries.data?.length && (
        <Accordion variant="contained">
          {deliveries.data.map((d) => (
            <Accordion.Item key={d.id} value={String(d.id)}>
              <Accordion.Control>
                <Group gap="xs" wrap="nowrap">
                  <Badge size="xs" color={statusColor(d.status)} variant="light">
                    {t(`webhooks.status.${d.status}`)}
                  </Badge>
                  <Code>{d.event}</Code>
                  <Text size="xs" c="dimmed">
                    {formatDateTime(d.createdAt, i18n.language)}
                    {d.responseStatus != null && ` · HTTP ${d.responseStatus}`}
                    {d.attempts > 1 && ` · ${t('webhooks.attempts', { count: d.attempts })}`}
                  </Text>
                </Group>
              </Accordion.Control>
              <Accordion.Panel>
                <Stack gap="xs">
                  {d.lastError && (
                    <Text size="sm" c="red">
                      {d.lastError}
                    </Text>
                  )}
                  {d.nextAttemptAt && d.attempts > 0 && (
                    <Text size="xs" c="dimmed">
                      {t('webhooks.nextAttempt', { date: formatDateTime(d.nextAttemptAt, i18n.language) })}
                    </Text>
                  )}
                  <Code block style={{ maxHeight: 320, overflow: 'auto' }}>
                    {prettyJson(d.body)}
                  </Code>
                  {d.status === 'FAILED' && (
                    <Group>
                      <Button
                        size="xs"
                        variant="default"
                        loading={retry.isPending && retry.variables?.id === d.id}
                        onClick={() => retry.mutate(d)}
                      >
                        {t('webhooks.retry')}
                      </Button>
                    </Group>
                  )}
                </Stack>
              </Accordion.Panel>
            </Accordion.Item>
          ))}
        </Accordion>
      )}
      <Modal opened={confirmRotate} onClose={() => setConfirmRotate(false)} title={t('webhooks.rotate')}>
        <Stack>
          <Text size="sm">{t('webhooks.rotateConfirm')}</Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setConfirmRotate(false)}>
              {t('common.cancel')}
            </Button>
            <Button
              loading={rotate.isPending}
              onClick={() => {
                setConfirmRotate(false);
                rotate.mutate();
              }}
            >
              {t('webhooks.rotate')}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}

function prettyJson(text: string): string {
  try {
    return JSON.stringify(JSON.parse(text), null, 2);
  } catch {
    return text;
  }
}

/** The signing secret, shown once after creating the webhook or making a new secret. */
function SecretModal({ secret, onClose }: { secret: string | null; onClose: () => void }) {
  const { t } = useTranslation();
  return (
    <Modal opened={secret !== null} onClose={onClose} title={t('webhooks.secretTitle')} size="lg">
      <Stack>
        <Alert color="green">{t('webhooks.secretOnce')}</Alert>
        <Group gap="xs" wrap="nowrap">
          <Code block style={{ flex: 1, wordBreak: 'break-all', whiteSpace: 'pre-wrap' }}>
            {secret}
          </Code>
          <CopyButton value={secret ?? ''}>
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
          {t('webhooks.secretUse')}
        </Text>
        <Group justify="flex-end">
          <Button onClick={onClose}>{t('tokens.done')}</Button>
        </Group>
      </Stack>
    </Modal>
  );
}
