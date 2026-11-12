// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import {
  ActionIcon,
  Alert,
  Badge,
  Button,
  Code,
  Group,
  Paper,
  SimpleGrid,
  Stack,
  Switch,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { IconMailForward, IconPlus, IconRefresh, IconStar, IconStarFilled, IconTrash } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { del, patch, post, seg } from '../../api/client';
import { keys, useAdminLanguages, useFailedMails, useMailStatus, useMailTemplates } from '../../api/queries';
import type { LanguageResponse } from '../../api/types';
import { ErrorAlert, PageLoader } from '../../components/QueryState';
import { fieldErrorMap } from '../../lib/apiError';
import { formatDateTime } from '../../lib/format';
import { notifyError, notifySaved } from '../../lib/notify';

/** Mail status, templates, languages and failed mails. */
export function MailSettingsPage() {
  return (
    <Stack gap="xl">
      <MailStatusSection />
      <TemplatesSection />
      <LanguagesSection />
      <FailedMailsSection />
    </Stack>
  );
}

function MailStatusSection() {
  const { t } = useTranslation();
  const status = useMailStatus();
  const test = useMutation({
    mutationFn: () => post('/api/v1/admin/mail/test'),
    onSuccess: () => notifySaved(t('admin.mail.testSent')),
    onError: notifyError,
  });
  if (status.isPending) return <PageLoader />;
  if (status.isError) return <ErrorAlert error={status.error} />;
  const s = status.data;

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={4}>{t('admin.mail.status')}</Title>
        <Button
          variant="default"
          leftSection={<IconMailForward size={16} />}
          disabled={!s.configured}
          loading={test.isPending}
          onClick={() => test.mutate()}
        >
          {t('admin.mail.sendTest')}
        </Button>
      </Group>
      {!s.configured && (
        <Alert color="yellow" title={t('admin.mail.notConfigured')}>
          {t('admin.mail.notConfiguredHelp')}
        </Alert>
      )}
      <SimpleGrid cols={{ base: 1, sm: 2, md: 4 }}>
        <Fact label={t('admin.mail.server')}>
          {s.configured ? `${s.host}:${s.port} (${s.security})` : t('admin.mail.none')}
        </Fact>
        <Fact label={t('admin.mail.from')}>{s.from}</Fact>
        <Fact label={t('admin.mail.baseUrl')}>{s.baseUrl}</Fact>
        <Fact label={t('admin.mail.queue')}>
          {t('admin.mail.queueCounts', {
            pending: s.outbox.pending,
            sent: s.outbox.sentLastDay,
            failed: s.outbox.failed,
          })}
        </Fact>
      </SimpleGrid>
    </Stack>
  );
}

function Fact({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <Paper withBorder p="sm">
      <Text size="xs" c="dimmed">
        {label}
      </Text>
      <Text size="sm" style={{ wordBreak: 'break-word' }}>
        {children}
      </Text>
    </Paper>
  );
}

function TemplatesSection() {
  const { t } = useTranslation();
  const templates = useMailTemplates();
  if (templates.isPending) return <PageLoader />;
  if (templates.isError) return <ErrorAlert error={templates.error} />;

  return (
    <Stack>
      <div>
        <Title order={4}>{t('admin.mail.templates')}</Title>
        <Text size="sm" c="dimmed">
          {t('admin.mail.templatesIntro')}
        </Text>
      </div>
      <Table verticalSpacing="sm">
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t('admin.mail.template')}</Table.Th>
            <Table.Th>{t('admin.mail.languages')}</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {templates.data.map((tpl) => (
            <Table.Tr key={tpl.key}>
              <Table.Td>
                <Text fw={500} size="sm">
                  {tpl.name}
                </Text>
                <Text size="xs" c="dimmed">
                  {tpl.description}
                </Text>
              </Table.Td>
              <Table.Td>
                <Group gap="xs">
                  {tpl.languages.map((l) => (
                    <Button
                      key={l.language}
                      component={Link}
                      to={`/admin/email/templates/${seg(tpl.key)}/${seg(l.language)}`}
                      size="compact-sm"
                      variant={l.customized ? 'light' : 'default'}
                      title={l.customized ? t('admin.mail.customized') : t('admin.mail.builtIn')}
                    >
                      {l.language.toUpperCase()}
                      {l.customized && ' ✎'}
                    </Button>
                  ))}
                </Group>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
      <Text size="xs" c="dimmed">
        {t('admin.mail.customizedLegend')}
      </Text>
    </Stack>
  );
}

function LanguagesSection() {
  const { t } = useTranslation();
  const languages = useAdminLanguages();
  const queryClient = useQueryClient();
  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: keys.adminLanguages });
    void queryClient.invalidateQueries({ queryKey: keys.languages });
    void queryClient.invalidateQueries({ queryKey: keys.mailTemplates });
  };
  const update = useMutation({
    mutationFn: ({ language, request }: { language: LanguageResponse; request: Record<string, unknown> }) =>
      patch(`/api/v1/admin/languages/${seg(language.code)}`, request),
    onSuccess: invalidate,
    onError: notifyError,
  });
  const remove = useMutation({
    mutationFn: (language: LanguageResponse) => del(`/api/v1/admin/languages/${seg(language.code)}`),
    onSuccess: invalidate,
    onError: notifyError,
  });
  const form = useForm({
    initialValues: { code: '', name: '' },
    validate: {
      code: (v) => (/^[a-z]{2,3}(-[a-z0-9]{2,8})?$/.test(v) ? null : t('admin.mail.languageCodeHelp')),
      name: (v) => (v.trim() ? null : t('validation.required')),
    },
  });
  const create = useMutation({
    mutationFn: (v: { code: string; name: string }) => post('/api/v1/admin/languages', v),
    onSuccess: () => {
      form.reset();
      invalidate();
    },
    onError: (e) => {
      const errors = fieldErrorMap(e);
      if (Object.keys(errors).length) form.setErrors(errors);
      else notifyError(e);
    },
  });

  if (languages.isPending) return <PageLoader />;
  if (languages.isError) return <ErrorAlert error={languages.error} />;

  return (
    <Stack>
      <div>
        <Title order={4}>{t('admin.mail.languages')}</Title>
        <Text size="sm" c="dimmed">
          {t('admin.mail.languagesIntro')}
        </Text>
      </div>
      <Table verticalSpacing="xs">
        <Table.Thead>
          <Table.Tr>
            <Table.Th w={90}>{t('admin.mail.code')}</Table.Th>
            <Table.Th>{t('admin.mail.languageName')}</Table.Th>
            <Table.Th w={110}>{t('admin.mail.enabled')}</Table.Th>
            <Table.Th w={80} />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {languages.data.map((l) => (
            <Table.Tr key={l.code}>
              <Table.Td>
                <Code>{l.code}</Code>
              </Table.Td>
              <Table.Td>
                <Group gap="xs">
                  <Text size="sm">{l.name}</Text>
                  {l.isDefault && (
                    <Badge size="xs" variant="light">
                      {t('admin.mail.default')}
                    </Badge>
                  )}
                </Group>
              </Table.Td>
              <Table.Td>
                <Switch
                  aria-label={t('admin.mail.enabled')}
                  checked={l.enabled}
                  disabled={l.isDefault}
                  onChange={(e) => update.mutate({ language: l, request: { enabled: e.currentTarget.checked } })}
                />
              </Table.Td>
              <Table.Td>
                <Group gap={4} wrap="nowrap">
                  <ActionIcon
                    variant="subtle"
                    color={l.isDefault ? 'yellow' : 'gray'}
                    disabled={l.isDefault}
                    aria-label={t('admin.mail.makeDefault', { name: l.name })}
                    title={l.isDefault ? t('admin.mail.default') : t('admin.mail.makeDefault', { name: l.name })}
                    onClick={() => update.mutate({ language: l, request: { isDefault: true } })}
                  >
                    {l.isDefault ? <IconStarFilled size={16} /> : <IconStar size={16} />}
                  </ActionIcon>
                  <ActionIcon
                    variant="subtle"
                    color="gray"
                    disabled={l.isDefault}
                    aria-label={t('common.delete')}
                    title={t('common.delete')}
                    onClick={() =>
                      window.confirm(t('admin.mail.deleteLanguageConfirm', { name: l.name })) && remove.mutate(l)
                    }
                  >
                    <IconTrash size={16} />
                  </ActionIcon>
                </Group>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
      <form onSubmit={form.onSubmit((v) => create.mutate({ code: v.code.trim(), name: v.name.trim() }))}>
        <Group align="flex-start" gap="xs">
          <TextInput
            placeholder={t('admin.mail.code')}
            aria-label={t('admin.mail.code')}
            w={110}
            {...form.getInputProps('code')}
            onChange={(e) => form.setFieldValue('code', e.currentTarget.value.toLowerCase())}
          />
          <TextInput
            placeholder={t('admin.mail.languageName')}
            aria-label={t('admin.mail.languageName')}
            w={220}
            {...form.getInputProps('name')}
          />
          <Button type="submit" variant="default" leftSection={<IconPlus size={16} />} loading={create.isPending}>
            {t('admin.mail.addLanguage')}
          </Button>
        </Group>
      </form>
    </Stack>
  );
}

function FailedMailsSection() {
  const { t, i18n } = useTranslation();
  const failed = useFailedMails();
  const queryClient = useQueryClient();
  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: keys.failedMails });
    void queryClient.invalidateQueries({ queryKey: keys.mailStatus });
  };
  const retry = useMutation({
    mutationFn: (id: number) => post(`/api/v1/admin/mail/failed/${id}/retry`),
    onSuccess: invalidate,
    onError: notifyError,
  });
  const discard = useMutation({
    mutationFn: (id: number) => del(`/api/v1/admin/mail/failed/${id}`),
    onSuccess: invalidate,
    onError: notifyError,
  });
  if (!failed.data || failed.data.length === 0) return null;

  return (
    <Stack>
      <div>
        <Title order={4}>{t('admin.mail.failed')}</Title>
        <Text size="sm" c="dimmed">
          {t('admin.mail.failedIntro')}
        </Text>
      </div>
      <Table verticalSpacing="xs">
        <Table.Thead>
          <Table.Tr>
            <Table.Th>{t('admin.mail.to')}</Table.Th>
            <Table.Th>{t('admin.mail.subject')}</Table.Th>
            <Table.Th visibleFrom="md">{t('admin.mail.error')}</Table.Th>
            <Table.Th visibleFrom="sm">{t('admin.mail.created')}</Table.Th>
            <Table.Th w={80} />
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {failed.data.map((m) => (
            <Table.Tr key={m.id}>
              <Table.Td>{m.toAddress}</Table.Td>
              <Table.Td>
                <Text size="sm" lineClamp={1}>
                  {m.subject}
                </Text>
              </Table.Td>
              <Table.Td visibleFrom="md">
                <Text size="xs" c="red" lineClamp={2} title={m.lastError ?? undefined}>
                  {m.lastError}
                </Text>
              </Table.Td>
              <Table.Td visibleFrom="sm">
                <Text size="sm" c="dimmed">
                  {formatDateTime(m.createdAt, i18n.language)}
                </Text>
              </Table.Td>
              <Table.Td>
                <Group gap={4} wrap="nowrap">
                  <ActionIcon
                    variant="subtle"
                    color="gray"
                    aria-label={t('admin.mail.retry')}
                    title={t('admin.mail.retry')}
                    onClick={() => retry.mutate(m.id)}
                  >
                    <IconRefresh size={16} />
                  </ActionIcon>
                  <ActionIcon
                    variant="subtle"
                    color="gray"
                    aria-label={t('admin.mail.discard')}
                    title={t('admin.mail.discard')}
                    onClick={() => discard.mutate(m.id)}
                  >
                    <IconTrash size={16} />
                  </ActionIcon>
                </Group>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Stack>
  );
}
