// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Anchor, Badge, Breadcrumbs, Button, Group, Pagination, Stack, Text, Title } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { IconPlus, IconSearch, IconSettings } from '@tabler/icons-react';
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import { useProject, useProjectIssues, useProjectPermissions } from '../api/queries';
import { CreateIssueModal } from '../components/CreateIssueModal';
import { IssueTable } from '../components/IssueTable';
import { ErrorAlert, isNotFound, PageLoader } from '../components/QueryState';
import { NotFoundPage } from './NotFoundPage';

export function ProjectPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const projectKey = useParams().projectKey!;
  const [params, setParams] = useSearchParams();
  const page = Math.max(0, Number(params.get('page') ?? '1') - 1) || 0;
  const project = useProject(projectKey);
  const permissions = useProjectPermissions(projectKey).data;
  const issues = useProjectIssues(projectKey, page);
  const [creating, createModal] = useDisclosure(false);

  // A former key still works; show the current one in the address bar.
  const currentKey = project.data?.key;
  useEffect(() => {
    if (currentKey && currentKey !== projectKey) navigate(`/projects/${currentKey}`, { replace: true });
  }, [currentKey, projectKey, navigate]);

  if (project.isPending) return <PageLoader />;
  if (project.isError) {
    return isNotFound(project.error) ? <NotFoundPage message={t('project.notFound')} /> : <ErrorAlert error={project.error} />;
  }
  const p = project.data;

  return (
    <Stack>
      <Breadcrumbs>
        <Anchor component={Link} to="/projects" size="sm">
          {t('projects.title')}
        </Anchor>
        <Text size="sm">{p.name}</Text>
      </Breadcrumbs>
      <Group justify="space-between" align="flex-start">
        <div>
          <Group gap="xs">
            <Title order={2}>{p.name}</Title>
            {p.archived && (
              <Badge color="gray" variant="light">
                {t('project.archived')}
              </Badge>
            )}
          </Group>
          <Text c="dimmed" size="sm">
            {p.key}
            {p.lead && ` · ${t('project.lead')}: ${p.lead.displayName}`}
          </Text>
        </div>
        <Group gap="xs">
          {permissions?.has('ADMINISTER_PROJECT') && (
            <Button
              variant="default"
              leftSection={<IconSettings size={16} />}
              component={Link}
              to={`/projects/${p.key}/settings`}
            >
              {t('settings.title')}
            </Button>
          )}
          <Button
            variant="default"
            leftSection={<IconSearch size={16} />}
            component={Link}
            to={`/issues?project=${p.id}&resolution=unresolved`}
          >
            {t('project.searchIssues')}
          </Button>
          {permissions?.has('CREATE_ISSUE') && (
            <Button leftSection={<IconPlus size={16} />} onClick={createModal.open}>
              {t('issue.create')}
            </Button>
          )}
        </Group>
      </Group>
      {p.description && (
        <Text className="bw-pre-wrap" size="sm">
          {p.description}
        </Text>
      )}

      {issues.isPending ? (
        <PageLoader />
      ) : issues.isError ? (
        <ErrorAlert error={issues.error} />
      ) : issues.data.items.length === 0 ? (
        <Text c="dimmed">{t('project.noIssues')}</Text>
      ) : (
        <>
          <IssueTable issues={issues.data.items} />
          <Group justify="space-between">
            <Text size="sm" c="dimmed">
              {t('project.issueCount', { count: issues.data.total })}
            </Text>
            {issues.data.total > issues.data.size && (
              <Pagination
                total={Math.ceil(issues.data.total / issues.data.size)}
                value={page + 1}
                onChange={(n) => setParams(n > 1 ? { page: String(n) } : {})}
              />
            )}
          </Group>
        </>
      )}

      <CreateIssueModal
        projectKey={p.key}
        opened={creating}
        onClose={createModal.close}
        onCreated={(issue) => navigate(`/browse/${issue.key}`)}
      />
    </Stack>
  );
}
