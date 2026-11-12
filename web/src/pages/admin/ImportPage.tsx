// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Anchor, Badge, Button, Card, Group, Progress, SimpleGrid, Stack, Table, Text, Title } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { useImportJobs } from '../../api/queries';
import type { ImportJob } from '../../api/types';
import { ErrorAlert, PageLoader } from '../../components/QueryState';
import { formatDateTime } from '../../lib/format';

/** The trackers BuggyWuggy can import from, now or later. */
const SOURCES = [
  { kind: 'forgejo', available: true, url: 'https://codeberg.org' },
  { kind: 'jira', available: false, url: 'https://your-site.atlassian.net' },
  { kind: 'gitlab', available: false, url: 'https://gitlab.com' },
  { kind: 'github', available: false, url: 'https://github.com' },
  { kind: 'redmine', available: false, url: '' },
  { kind: 'trac', available: false, url: '' },
] as const;

const STATUS_COLOR: Record<ImportJob['status'], string> = {
  RUNNING: 'blue',
  SUCCEEDED: 'green',
  FAILED: 'red',
  CANCELLED: 'gray',
  INTERRUPTED: 'orange',
};

export function ImportStatusBadge({ status }: { status: ImportJob['status'] }) {
  const { t } = useTranslation();
  return <Badge color={STATUS_COLOR[status]}>{t(`admin.importer.status.${status}`)}</Badge>;
}

export function ImportProgress({ job }: { job: ImportJob }) {
  const { issuesDone, issuesTotal } = job.counters;
  const value = issuesTotal ? Math.round((issuesDone / issuesTotal) * 100) : job.status === 'RUNNING' ? 0 : 100;
  return <Progress value={value} animated={job.status === 'RUNNING'} size="sm" />;
}

export function ImportPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  return (
    <Stack gap="xl">
      <Stack>
        <Text c="dimmed">{t('admin.importer.intro')}</Text>
        <SimpleGrid cols={{ base: 1, sm: 2, md: 3 }}>
          {SOURCES.map((s) => (
            <Card key={s.kind} withBorder padding="md">
              <Stack gap="xs" h="100%">
                <Group justify="space-between">
                  <Text fw={600}>{t(`admin.importer.source.${s.kind}.name`)}</Text>
                  {!s.available && <Badge variant="light">{t('admin.importer.comingLater')}</Badge>}
                </Group>
                <Text size="sm" c="dimmed" style={{ flex: 1 }}>
                  {t(`admin.importer.source.${s.kind}.description`)}
                </Text>
                {s.url && (
                  <Text size="xs" c="dimmed">
                    {t('admin.importer.defaultUrl', { url: s.url })}
                  </Text>
                )}
                <Button
                  variant={s.available ? 'filled' : 'default'}
                  disabled={!s.available}
                  onClick={() => navigate(`/admin/import/${s.kind}`)}
                >
                  {t('admin.importer.start')}
                </Button>
              </Stack>
            </Card>
          ))}
        </SimpleGrid>
      </Stack>
      <ImportJobs />
    </Stack>
  );
}

function ImportJobs() {
  const { t, i18n } = useTranslation();
  const jobs = useImportJobs();
  if (jobs.isPending) return <PageLoader />;
  if (jobs.isError) return <ErrorAlert error={jobs.error} />;
  return (
    <Stack>
      <Title order={4}>{t('admin.importer.history')}</Title>
      {jobs.data.length === 0 ? (
        <Text c="dimmed" size="sm">
          {t('admin.importer.noJobs')}
        </Text>
      ) : (
        <Table.ScrollContainer minWidth={700}>
          <Table striped>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('admin.importer.started')}</Table.Th>
                <Table.Th>{t('admin.importer.sourceColumn')}</Table.Th>
                <Table.Th>{t('admin.importer.project')}</Table.Th>
                <Table.Th>{t('admin.importer.statusColumn')}</Table.Th>
                <Table.Th w="20%">{t('admin.importer.progress')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {jobs.data.map((job) => (
                <Table.Tr key={job.id}>
                  <Table.Td>
                    <Anchor component={Link} to={`/admin/import/jobs/${job.id}`} size="sm">
                      {formatDateTime(job.createdAt, i18n.language)}
                    </Anchor>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm">{job.sourcePath}</Text>
                    <Text size="xs" c="dimmed">
                      {job.baseUrl}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    {job.projectKey ? (
                      <Anchor component={Link} to={`/projects/${job.projectKey}`} size="sm">
                        {job.projectName}
                      </Anchor>
                    ) : (
                      '—'
                    )}
                  </Table.Td>
                  <Table.Td>
                    <ImportStatusBadge status={job.status} />
                  </Table.Td>
                  <Table.Td>
                    <ImportProgress job={job} />
                    <Text size="xs" c="dimmed">
                      {t('admin.importer.issueCount', {
                        done: job.counters.issuesDone,
                        total: job.counters.issuesTotal,
                      })}
                    </Text>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
    </Stack>
  );
}
