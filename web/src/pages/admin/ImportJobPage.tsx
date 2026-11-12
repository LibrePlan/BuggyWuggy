// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import {
  Alert,
  Anchor,
  Button,
  Code,
  Group,
  Modal,
  Paper,
  PasswordInput,
  ScrollArea,
  SimpleGrid,
  Stack,
  Text,
  Title,
} from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router';
import { get, post } from '../../api/client';
import { keys, useImportJob } from '../../api/queries';
import type { ImportJob, ImportLogLine } from '../../api/types';
import { ErrorAlert, PageLoader } from '../../components/QueryState';
import { formatDateTime } from '../../lib/format';
import { notifyError } from '../../lib/notify';
import { ImportProgress, ImportStatusBadge } from './ImportPage';

const LEVEL_COLOR: Record<ImportLogLine['level'], string | undefined> = {
  INFO: undefined,
  WARN: 'var(--mantine-color-orange-text)',
  ERROR: 'var(--mantine-color-red-text)',
};

export function ImportJobPage() {
  const { t, i18n } = useTranslation();
  const id = Number(useParams().jobId);
  const job = useImportJob(id);
  if (job.isPending) return <PageLoader />;
  if (job.isError) return <ErrorAlert error={job.error} />;
  const j = job.data;
  const c = j.counters;

  return (
    <Stack>
      <Group justify="space-between">
        <Group gap="sm">
          <Title order={3}>{t('admin.importer.job.title', { source: j.sourcePath })}</Title>
          <ImportStatusBadge status={j.status} />
        </Group>
        <Anchor component={Link} to="/admin/import" size="sm">
          {t('admin.importer.back')}
        </Anchor>
      </Group>
      <Text size="sm" c="dimmed">
        {t('admin.importer.job.meta', {
          url: j.baseUrl,
          started: formatDateTime(j.createdAt, i18n.language),
          by: j.createdBy ?? '—',
        })}
        {j.projectKey && (
          <>
            {' · '}
            <Anchor component={Link} to={`/projects/${j.projectKey}`} size="sm">
              {j.projectName}
            </Anchor>
          </>
        )}
      </Text>
      <ImportProgress job={j} />
      <SimpleGrid cols={{ base: 2, sm: 3, md: 6 }}>
        <Fact label={t('admin.importer.job.issues')} value={`${c.issuesDone} / ${c.issuesTotal}`} />
        <Fact label={t('admin.importer.job.imported')} value={c.issuesImported} />
        <Fact label={t('admin.importer.job.comments')} value={c.comments} />
        <Fact label={t('admin.importer.job.attachments')} value={c.attachments} />
        <Fact label={t('admin.importer.job.users')} value={c.usersCreated} />
        <Fact label={t('admin.importer.job.warnings')} value={c.warnings} />
      </SimpleGrid>
      {j.message && j.status !== 'SUCCEEDED' && (
        <Alert color={j.status === 'FAILED' ? 'red' : 'orange'}>{j.message}</Alert>
      )}
      <JobActions job={j} />
      <JobLog job={j} />
    </Stack>
  );
}

function Fact({ label, value }: { label: string; value: React.ReactNode }) {
  return (
    <Paper withBorder p="sm">
      <Text size="xs" c="dimmed">
        {label}
      </Text>
      <Text fw={600}>{value}</Text>
    </Paper>
  );
}

function JobActions({ job }: { job: ImportJob }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [askToken, setAskToken] = useState(false);
  const [token, setToken] = useState('');
  const cancel = useMutation({
    mutationFn: () => post(`/api/v1/admin/import/jobs/${job.id}/cancel`),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.importJob(job.id) }),
    onError: notifyError,
  });
  const retry = useMutation({
    mutationFn: (withToken: string | null) =>
      post<ImportJob>(`/api/v1/admin/import/jobs/${job.id}/retry`, { token: withToken }),
    onSuccess: (next) => {
      setAskToken(false);
      setToken('');
      queryClient.invalidateQueries({ queryKey: keys.importJobs });
      navigate(`/admin/import/jobs/${next.id}`);
    },
    onError: notifyError,
  });

  if (job.status === 'RUNNING') {
    return (
      <Group>
        <Button color="red" variant="light" loading={cancel.isPending} onClick={() => cancel.mutate()}>
          {t('admin.importer.job.cancel')}
        </Button>
      </Group>
    );
  }
  return (
    <Stack gap="xs">
      <Group>
        <Button
          variant="default"
          loading={retry.isPending}
          disabled={!job.projectId}
          onClick={() => (job.credentialsAvailable ? retry.mutate(null) : setAskToken(true))}
        >
          {t('admin.importer.job.retry')}
        </Button>
      </Group>
      <Text size="xs" c="dimmed">
        {t(job.credentialsAvailable ? 'admin.importer.job.retryHelpToken' : 'admin.importer.job.retryHelp')}
      </Text>
      <Modal opened={askToken} onClose={() => setAskToken(false)} title={t('admin.importer.job.retry')}>
        <Stack>
          <Text size="sm">{t('admin.importer.job.tokenAgain')}</Text>
          <PasswordInput
            label={t('admin.importer.forgejo.token')}
            autoComplete="off"
            value={token}
            onChange={(e) => setToken(e.currentTarget.value)}
          />
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setAskToken(false)}>
              {t('common.cancel')}
            </Button>
            <Button loading={retry.isPending} onClick={() => retry.mutate(token || null)}>
              {t('admin.importer.job.retry')}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}

/** The log, extended while the job runs. */
function JobLog({ job }: { job: ImportJob }) {
  const { t, i18n } = useTranslation();
  const [lines, setLines] = useState<ImportLogLine[]>([]);
  const [error, setError] = useState<unknown>(null);
  const last = useRef(0);
  const viewport = useRef<HTMLDivElement>(null);
  const running = job.status === 'RUNNING';

  useEffect(() => {
    setLines([]);
    last.current = 0;
  }, [job.id]);

  useEffect(() => {
    let stopped = false;
    async function load() {
      try {
        const more = await get<ImportLogLine[]>(`/api/v1/admin/import/jobs/${job.id}/log`, { after: last.current });
        const newest = more.at(-1);
        if (stopped || !newest) return;
        last.current = newest.id;
        setLines((l) => [...l, ...more]);
        requestAnimationFrame(() =>
          viewport.current?.scrollTo({ top: viewport.current.scrollHeight }),
        );
      } catch (e) {
        if (!stopped) setError(e);
      }
    }
    load();
    if (!running) return () => {
      stopped = true;
    };
    const timer = setInterval(load, 1500);
    return () => {
      stopped = true;
      clearInterval(timer);
    };
  }, [job.id, running]);

  return (
    <Stack gap="xs">
      <Title order={5}>{t('admin.importer.job.log')}</Title>
      {error != null && <ErrorAlert error={error} />}
      <Paper withBorder p={0}>
        <ScrollArea h={360} viewportRef={viewport} p="sm">
          {lines.length === 0 ? (
            <Text size="sm" c="dimmed">
              {t('admin.importer.job.noLog')}
            </Text>
          ) : (
            lines.map((l) => (
              <Code key={l.id} block bg="transparent" p={0} style={{ color: LEVEL_COLOR[l.level], whiteSpace: 'pre-wrap' }}>
                {formatDateTime(l.at, i18n.language)}  {l.message}
              </Code>
            ))
          )}
        </ScrollArea>
      </Paper>
    </Stack>
  );
}
