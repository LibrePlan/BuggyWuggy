// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Alert, Anchor, Breadcrumbs, Stack, Tabs, Text, Title } from '@mantine/core';
import { IconArchive } from '@tabler/icons-react';
import { useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import { useMyself, useProject, useProjectPermissions } from '../api/queries';
import { ErrorAlert, isNotFound, PageLoader } from '../components/QueryState';
import { ComponentsSettings } from '../components/settings/ComponentsSettings';
import { ProjectDetailsSettings } from '../components/settings/ProjectDetailsSettings';
import { RolesSettings } from '../components/settings/RolesSettings';
import { VersionsSettings } from '../components/settings/VersionsSettings';
import { NotFoundPage } from './NotFoundPage';

const TABS = ['details', 'components', 'versions', 'roles'] as const;
type Tab = (typeof TABS)[number];

/** Project settings, for project administrators (ADMINISTER_PROJECT). */
export function ProjectSettingsPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const projectKey = useParams().projectKey!;
  const [params, setParams] = useSearchParams();
  const tab: Tab = TABS.find((x) => x === params.get('tab')) ?? 'details';
  const project = useProject(projectKey);
  const permissions = useProjectPermissions(projectKey);
  const globalAdmin = useMyself().data?.globalPermissions.includes('ADMINISTER') ?? false;

  const currentKey = project.data?.key;
  useEffect(() => {
    if (currentKey && currentKey !== projectKey) navigate(`/projects/${currentKey}/settings${location.search}`, { replace: true });
  }, [currentKey, projectKey, navigate]);

  if (project.isPending || permissions.isPending) return <PageLoader />;
  if (project.isError) {
    return isNotFound(project.error) ? <NotFoundPage message={t('project.notFound')} /> : <ErrorAlert error={project.error} />;
  }
  if (!permissions.data?.has('ADMINISTER_PROJECT')) return <NotFoundPage message={t('project.notFound')} />;
  const p = project.data;
  const readOnly = p.archived;

  return (
    <Stack>
      <Breadcrumbs>
        <Anchor component={Link} to="/projects" size="sm">
          {t('projects.title')}
        </Anchor>
        <Anchor component={Link} to={`/projects/${p.key}`} size="sm">
          {p.name}
        </Anchor>
        <Text size="sm">{t('settings.title')}</Text>
      </Breadcrumbs>
      <Title order={2}>{t('settings.titleFor', { name: p.name })}</Title>
      {p.archived && (
        <Alert color="gray" icon={<IconArchive />}>
          {globalAdmin ? t('settings.archivedNoticeAdmin') : t('settings.archivedNotice')}
        </Alert>
      )}
      <Tabs
        value={tab}
        onChange={(v) => setParams(v && v !== 'details' ? { tab: v } : {}, { replace: true })}
        keepMounted={false}
      >
        <Tabs.List>
          {TABS.map((x) => (
            <Tabs.Tab key={x} value={x}>
              {t(`settings.tab.${x}`)}
            </Tabs.Tab>
          ))}
        </Tabs.List>
        <Tabs.Panel value="details" pt="md">
          <ProjectDetailsSettings project={p} globalAdmin={globalAdmin} />
        </Tabs.Panel>
        <Tabs.Panel value="components" pt="md">
          <ComponentsSettings projectKey={p.key} readOnly={readOnly} />
        </Tabs.Panel>
        <Tabs.Panel value="versions" pt="md">
          <VersionsSettings projectKey={p.key} readOnly={readOnly} />
        </Tabs.Panel>
        <Tabs.Panel value="roles" pt="md">
          <RolesSettings projectKey={p.key} readOnly={readOnly} />
        </Tabs.Panel>
      </Tabs>
    </Stack>
  );
}
