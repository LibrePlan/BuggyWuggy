// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import {
  Alert,
  Anchor,
  Badge,
  Box,
  Breadcrumbs,
  Button,
  Code,
  Grid,
  Group,
  Modal,
  Paper,
  ScrollArea,
  SegmentedControl,
  Stack,
  Tabs,
  Text,
  TextInput,
  Textarea,
  Title,
  UnstyledButton,
} from '@mantine/core';
import { IconEye, IconInfoCircle, IconMailForward, IconRestore } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router';
import { del, post, put, seg } from '../../api/client';
import { keys, useMailStatus, useMailTemplate, useMailTemplates } from '../../api/queries';
import type { KeywordInfo, PreviewResponse, TemplateResponse } from '../../api/types';
import { ErrorAlert, isNotFound, PageLoader } from '../../components/QueryState';
import { ApiError } from '../../lib/apiError';
import { formatDateTime } from '../../lib/format';
import { notifyError, notifySaved } from '../../lib/notify';
import { insertAt } from '../../lib/text';
import { NotFoundPage } from '../NotFoundPage';

type Field = 'subject' | 'body';

/** Edits one mail template in one language, with the keyword help next to it. */
export function MailTemplatePage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { templateKey = '', language = '' } = useParams();
  const template = useMailTemplate(templateKey, language);
  const languages = useMailTemplates().data?.find((x) => x.key === templateKey)?.languages ?? [];
  const [dirty, setDirty] = useState(false);

  if (template.isPending) return <PageLoader />;
  if (template.isError) {
    return isNotFound(template.error) ? <NotFoundPage /> : <ErrorAlert error={template.error} />;
  }
  const tpl = template.data;

  return (
    <Stack>
      <Breadcrumbs>
        <Anchor component={Link} to="/admin/email" size="sm">
          {t('admin.email')}
        </Anchor>
        <Text size="sm">{tpl.name}</Text>
      </Breadcrumbs>
      <Group justify="space-between" align="flex-end">
        <div>
          <Title order={3}>{tpl.name}</Title>
          <Text size="sm" c="dimmed">
            {tpl.description}
          </Text>
        </div>
        {languages.length > 1 && (
          <SegmentedControl
            aria-label={t('admin.mail.languages')}
            value={language}
            data={languages.map((l) => ({ value: l.language, label: l.language.toUpperCase() }))}
            onChange={(next) => {
              if (!dirty || window.confirm(t('admin.mail.discardChanges'))) {
                navigate(`/admin/email/templates/${seg(templateKey)}/${seg(next)}`);
              }
            }}
          />
        )}
      </Group>
      {/* Remount per template and language, so the form starts from the loaded text. */}
      <TemplateEditor key={`${tpl.key}/${tpl.language}`} template={tpl} onDirtyChange={setDirty} />
    </Stack>
  );
}

function TemplateEditor({ template, onDirtyChange }: { template: TemplateResponse; onDirtyChange: (d: boolean) => void }) {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const mailConfigured = useMailStatus().data?.configured ?? false;
  const [subject, setSubject] = useState(template.subject);
  const [body, setBody] = useState(template.body);
  const [errors, setErrors] = useState<Partial<Record<Field, string>>>({});
  const [preview, setPreview] = useState<PreviewResponse | null>(null);
  const subjectRef = useRef<HTMLInputElement>(null);
  const bodyRef = useRef<HTMLTextAreaElement>(null);
  const lastField = useRef<Field>('body');
  const dirty = subject !== template.subject || body !== template.body;
  useEffect(() => onDirtyChange(dirty), [dirty, onDirtyChange]);

  const base = `/api/v1/admin/mail/templates/${seg(template.key)}/${seg(template.language)}`;
  const onSaved = (saved: TemplateResponse) => {
    queryClient.setQueryData(keys.mailTemplate(template.key, template.language), saved);
    void queryClient.invalidateQueries({ queryKey: keys.mailTemplates });
    setSubject(saved.subject);
    setBody(saved.body);
    setErrors({});
  };
  const showProblems = (e: unknown) => {
    if (e instanceof ApiError && e.fieldErrors.length > 0) {
      const byField: Partial<Record<Field, string>> = {};
      for (const fe of e.fieldErrors) {
        const field = fe.field as Field;
        byField[field] = byField[field] ? `${byField[field]}\n${fe.message}` : fe.message;
      }
      setErrors(byField);
    } else {
      notifyError(e);
    }
  };

  const save = useMutation({
    mutationFn: () => put<TemplateResponse>(base, { subject, body }),
    onSuccess: (saved) => {
      onSaved(saved);
      notifySaved();
    },
    onError: showProblems,
  });
  const reset = useMutation({
    mutationFn: () => del<TemplateResponse>(base),
    onSuccess: (saved) => {
      onSaved(saved);
      notifySaved(t('admin.mail.resetDone'));
    },
    onError: notifyError,
  });
  const showPreview = useMutation({
    mutationFn: () => post<PreviewResponse>(`${base}/preview`, { subject, body }),
    onSuccess: (result) => {
      const byField: Partial<Record<Field, string>> = {};
      for (const p of result.problems) {
        const field = p.field as Field;
        byField[field] = byField[field] ? `${byField[field]}\n${p.message}` : p.message;
      }
      setErrors(byField);
      setPreview(result);
    },
    onError: notifyError,
  });
  const test = useMutation({
    mutationFn: () => post(`${base}/test`, { subject, body }),
    onSuccess: () => notifySaved(t('admin.mail.testSent')),
    onError: showProblems,
  });

  function insert(keyword: KeywordInfo) {
    // Block keywords only work in the body.
    const field: Field = keyword.block ? 'body' : lastField.current;
    const element = field === 'subject' ? subjectRef.current : bodyRef.current;
    const value = field === 'subject' ? subject : body;
    const start = element?.selectionStart ?? value.length;
    const end = element?.selectionEnd ?? value.length;
    const result = insertAt(value, start, end, `{{${keyword.name}}}`);
    (field === 'subject' ? setSubject : setBody)(result.text);
    requestAnimationFrame(() => {
      element?.focus();
      element?.setSelectionRange(result.cursor, result.cursor);
    });
  }

  return (
    <Grid gap="lg">
      <Grid.Col span={{ base: 12, lg: 8 }}>
        <Stack>
          <Text size="sm" c="dimmed">
            {template.customized
              ? t('admin.mail.customizedBy', {
                  date: formatDateTime(template.updatedAt, i18n.language),
                  name: template.updatedBy?.displayName ?? '—',
                })
              : t('admin.mail.builtInHelp')}
          </Text>
          <TextInput
            ref={subjectRef}
            label={t('admin.mail.subject')}
            required
            value={subject}
            onChange={(e) => setSubject(e.currentTarget.value)}
            onFocus={() => (lastField.current = 'subject')}
            error={errors.subject && <span className="bw-pre-wrap">{errors.subject}</span>}
          />
          <Textarea
            ref={bodyRef}
            label={t('admin.mail.body')}
            description={t('admin.mail.bodyHelp')}
            required
            autosize
            minRows={18}
            maxRows={40}
            spellCheck={false}
            styles={{ input: { fontFamily: 'var(--mantine-font-family-monospace)', fontSize: 13 } }}
            value={body}
            onChange={(e) => setBody(e.currentTarget.value)}
            onFocus={() => (lastField.current = 'body')}
            error={errors.body && <span className="bw-pre-wrap">{errors.body}</span>}
          />
          <Group justify="space-between">
            <Group gap="xs">
              <Button
                variant="default"
                leftSection={<IconEye size={16} />}
                loading={showPreview.isPending}
                onClick={() => showPreview.mutate()}
              >
                {t('admin.mail.preview')}
              </Button>
              <Button
                variant="default"
                leftSection={<IconMailForward size={16} />}
                disabled={!mailConfigured}
                title={mailConfigured ? undefined : t('admin.mail.notConfigured')}
                loading={test.isPending}
                onClick={() => test.mutate()}
              >
                {t('admin.mail.sendTestToMe')}
              </Button>
              {template.customized && (
                <Button
                  variant="subtle"
                  color="red"
                  leftSection={<IconRestore size={16} />}
                  loading={reset.isPending}
                  onClick={() => window.confirm(t('admin.mail.resetConfirm')) && reset.mutate()}
                >
                  {t('admin.mail.reset')}
                </Button>
              )}
            </Group>
            <Group gap="xs">
              <Button
                variant="default"
                disabled={!dirty}
                onClick={() => {
                  setSubject(template.subject);
                  setBody(template.body);
                  setErrors({});
                }}
              >
                {t('common.cancel')}
              </Button>
              <Button disabled={!dirty} loading={save.isPending} onClick={() => save.mutate()}>
                {t('common.save')}
              </Button>
            </Group>
          </Group>
        </Stack>
      </Grid.Col>
      <Grid.Col span={{ base: 12, lg: 4 }}>
        <KeywordHelpBox template={template} onInsert={insert} />
      </Grid.Col>
      <PreviewModal preview={preview} onClose={() => setPreview(null)} />
    </Grid>
  );
}

/** The help next to the editor; its texts come from the server in the administrator's language. */
function KeywordHelpBox({ template, onInsert }: { template: TemplateResponse; onInsert: (k: KeywordInfo) => void }) {
  const help = template.help;
  return (
    <Paper withBorder p="md" bg="var(--mantine-color-blue-light)" style={{ position: 'sticky', top: 72 }}>
      <Stack gap="xs">
        <Group gap="xs">
          <IconInfoCircle size={18} />
          <Title order={5}>{help.title}</Title>
        </Group>
        <Text size="sm">{help.intro}</Text>
        <Text size="sm" fw={500}>
          {help.insert}
        </Text>
        <ScrollArea.Autosize mah="50vh" type="auto" offsetScrollbars>
          <Stack gap={6}>
            {template.keywords.map((k) => (
              <UnstyledButton
                key={k.name}
                onClick={() => onInsert(k)}
                className="bw-keyword"
                aria-label={`{{${k.name}}}: ${k.description}`}
              >
                <Group gap={6} wrap="nowrap" align="baseline">
                  <Code>{`{{${k.name}}}`}</Code>
                  {k.block && (
                    <Badge size="xs" variant="outline">
                      HTML
                    </Badge>
                  )}
                </Group>
                <Text size="xs" c="dimmed">
                  {k.description}
                </Text>
              </UnstyledButton>
            ))}
          </Stack>
        </ScrollArea.Autosize>
        <Text size="xs">{help.block}</Text>
        <Text size="xs">{help.html}</Text>
      </Stack>
    </Paper>
  );
}

function PreviewModal({ preview, onClose }: { preview: PreviewResponse | null; onClose: () => void }) {
  const { t } = useTranslation();
  return (
    <Modal opened={preview !== null} onClose={onClose} title={t('admin.mail.preview')} size="xl">
      {preview && (
        <Stack>
          {preview.problems.length > 0 && (
            <Alert color="red" title={t('admin.mail.problems')}>
              {preview.problems.map((p, i) => (
                <Text key={i} size="sm">
                  {p.message}
                </Text>
              ))}
            </Alert>
          )}
          <Text size="sm">
            <Text span c="dimmed">
              {t('admin.mail.subject')}:
            </Text>{' '}
            <b>{preview.subject}</b>
          </Text>
          <Text size="xs" c="dimmed">
            {t('admin.mail.sampleData')}
          </Text>
          <Tabs defaultValue="html">
            <Tabs.List>
              <Tabs.Tab value="html">HTML</Tabs.Tab>
              <Tabs.Tab value="text">{t('admin.mail.plainText')}</Tabs.Tab>
            </Tabs.List>
            <Tabs.Panel value="html" pt="sm">
              {/* sandbox without permissions: no scripts, forms or navigation of this page */}
              <Box
                component="iframe"
                title={t('admin.mail.preview')}
                sandbox=""
                srcDoc={preview.html}
                w="100%"
                h={480}
                style={{ border: '1px solid var(--mantine-color-default-border)', background: '#fff' }}
              />
            </Tabs.Panel>
            <Tabs.Panel value="text" pt="sm">
              <Paper withBorder p="sm">
                <Text size="sm" ff="monospace" className="bw-pre-wrap">
                  {preview.text}
                </Text>
              </Paper>
            </Tabs.Panel>
          </Tabs>
        </Stack>
      )}
    </Modal>
  );
}
