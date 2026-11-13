// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import {
  Alert,
  Anchor,
  Badge,
  Button,
  Checkbox,
  ColorSwatch,
  Group,
  List,
  Paper,
  PasswordInput,
  SegmentedControl,
  Select,
  SimpleGrid,
  Stack,
  Stepper,
  Table,
  Text,
  TextInput,
  Title,
} from '@mantine/core';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { post } from '../../api/client';
import { keys, useAllProjects, useIssueTypes, usePriorities } from '../../api/queries';
import type {
  ImportAnalysis,
  ImportConnection,
  ImportJob,
  ImportRepository,
  ImportStartRequest,
  LabelRule,
  UserRef,
} from '../../api/types';
import { AnyUserPicker } from '../../components/EntityPickers';
import { ErrorAlert } from '../../components/QueryState';
import { fieldErrorMap } from '../../lib/apiError';
import { notifyError } from '../../lib/notify';
import { PROJECT_KEY_PATTERN, suggestProjectKey } from '../../lib/projectKey';

/** The sources with a repository wizard, and what differs between them. */
export const REPOSITORY_SOURCES = {
  forgejo: { name: 'Forgejo', url: 'https://codeberg.org', tokenRequired: false, userField: false },
  github: { name: 'GitHub', url: 'https://github.com', tokenRequired: true, userField: false },
  gitlab: { name: 'GitLab', url: 'https://gitlab.com', tokenRequired: false, userField: false },
  // Jira: a project key instead of a repository, and basic authentication with e-mail address (or username).
  jira: { name: 'Jira', url: 'https://your-site.atlassian.net', tokenRequired: false, userField: true },
} as const;

export type RepositorySource = keyof typeof REPOSITORY_SOURCES;

/** A label rule as one select value: LABEL, IGNORE, ISSUE_TYPE:3, PRIORITY:2. */
function ruleValue(rule: LabelRule): string {
  return rule.id != null ? `${rule.target}:${rule.id}` : rule.target;
}

function ruleOf(value: string): LabelRule {
  const [target, id] = value.split(':');
  return { target: target as LabelRule['target'], id: id ? Number(id) : null };
}

/** The import wizard for trackers with repositories (Forgejo, GitHub, GitLab) or projects (Jira). */
export function RepositoryImportPage({ kind }: { kind: RepositorySource }) {
  const { t } = useTranslation();
  const source = REPOSITORY_SOURCES[kind];
  const BASE = `/api/v1/admin/import/${kind}`;
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [step, setStep] = useState(0);

  // 1. Connection
  const [url, setUrl] = useState<string>(source.url);
  const [token, setToken] = useState('');
  const [user, setUser] = useState('');
  // Basic authentication goes to the server as user:token; it is only kept in memory there too.
  const secret = source.userField && user.trim() ? `${user.trim()}:${token}` : token;
  const connect = useMutation({
    mutationFn: () => post<ImportConnection>(`${BASE}/connection`, { url, token: secret || null }),
    onSuccess: (c) => setUrl(c.url),
  });

  // 2. Repository and project
  const [query, setQuery] = useState('');
  const [repository, setRepository] = useState('');
  const search = useMutation({
    mutationFn: () =>
      post<ImportRepository[]>(`${BASE}/repositories`, { url, token: secret || null, query: query || null }),
  });
  const [target, setTarget] = useState<'new' | 'existing'>('new');
  const [newKey, setNewKey] = useState('');
  const [newName, setNewName] = useState('');
  const [projectId, setProjectId] = useState<string | null>(null);
  const projects = (useAllProjects().data ?? []).filter((p) => !p.archived);

  // 3. Mapping
  const analysis = useMutation({
    mutationFn: () => post<ImportAnalysis>(`${BASE}/analysis`, { url, token: secret || null, repository }),
    onSuccess: (a) => {
      setRules(Object.fromEntries(a.labels.map((l) => [l.name, l.suggestion])));
      setUsers(Object.fromEntries(a.users.map((u) => [u.id, u.suggestion])));
    },
  });
  const allTypes = useIssueTypes().data;
  const issueTypes = useMemo(() => (allTypes ?? []).filter((type) => type.hierarchyLevel === 0), [allTypes]);
  // A label may also become an epic (e.g. Jira's type:Epic); sub-tasks only come from the hierarchy.
  const labelTypes = useMemo(() => (allTypes ?? []).filter((type) => type.hierarchyLevel >= 0), [allTypes]);
  const priorities = usePriorities().data ?? [];
  const [issueTypeId, setIssueTypeId] = useState<string | null>(null);
  const [rules, setRules] = useState<Record<string, LabelRule>>({});
  const [users, setUsers] = useState<Record<string, UserRef | null>>({});
  const [matchUsernames, setMatchUsernames] = useState(true);
  useEffect(() => {
    const preferred = issueTypes.find((type) => type.name === 'Task') ?? issueTypes[0];
    if (!issueTypeId && preferred) setIssueTypeId(String(preferred.id));
  }, [issueTypes, issueTypeId]);

  const ruleOptions = useMemo(
    () => [
      {
        group: t('admin.importer.wizard.keep'),
        items: [
          { value: 'LABEL', label: t('admin.importer.wizard.asLabel') },
          { value: 'COMPONENT', label: t('admin.importer.wizard.asComponent') },
          { value: 'IGNORE', label: t('admin.importer.wizard.ignore') },
        ],
      },
      {
        group: t('admin.importer.wizard.asIssueType'),
        items: labelTypes.map((type) => ({ value: `ISSUE_TYPE:${type.id}`, label: type.name })),
      },
      {
        group: t('admin.importer.wizard.asPriority'),
        items: priorities.map((p) => ({ value: `PRIORITY:${p.id}`, label: p.name })),
      },
    ],
    [t, labelTypes, priorities],
  );

  // 4. Start
  const start = useMutation({
    mutationFn: (request: ImportStartRequest) => post<ImportJob>(`${BASE}/jobs`, request),
    onSuccess: (job) => {
      queryClient.invalidateQueries({ queryKey: keys.importJobs });
      navigate(`/admin/import/jobs/${job.id}`);
    },
    onError: notifyError,
  });
  const startErrors = fieldErrorMap(start.error);

  const keyValid = PROJECT_KEY_PATTERN.test(newKey);
  const targetValid = target === 'new' ? keyValid && newName.trim() !== '' : projectId != null;
  const chosenProject = projects.find((p) => String(p.id) === projectId);

  function chooseRepository(r: ImportRepository) {
    setRepository(r.fullName);
    // Jira: the key, with the project name as description.
    const project = kind === 'jira';
    const name = project ? (r.description ?? r.fullName) : (r.fullName.split('/').at(-1) ?? r.fullName);
    setNewName(name);
    setNewKey(project && PROJECT_KEY_PATTERN.test(r.fullName) ? r.fullName : suggestProjectKey(name));
  }

  function submit() {
    const chosen: Record<string, number> = {};
    for (const [id, user] of Object.entries(users)) if (user) chosen[id] = user.id;
    start.mutate({
      url,
      token: secret || null,
      repository,
      projectId: target === 'existing' ? Number(projectId) : null,
      newProject: target === 'new' ? { key: newKey, name: newName.trim() } : null,
      issueTypeId: Number(issueTypeId),
      labels: rules,
      users: chosen,
      matchUsernames,
    } as ImportStartRequest);
  }

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={3}>{t(`admin.importer.source.${kind}.title`)}</Title>
        <Anchor component={Link} to="/admin/import" size="sm">
          {t('admin.importer.back')}
        </Anchor>
      </Group>
      <Stepper active={step} onStepClick={(s) => s < step && setStep(s)} allowNextStepsSelect={false}>
        <Stepper.Step label={t('admin.importer.wizard.step.connection')}>
          <Stack maw={640} mt="md">
            <TextInput
              label={t('admin.importer.wizard.url')}
              description={t(`admin.importer.source.${kind}.urlHelp`)}
              required
              value={url}
              onChange={(e) => {
                setUrl(e.currentTarget.value);
                connect.reset();
              }}
            />
            {source.userField && (
              <TextInput
                label={t('admin.importer.wizard.user')}
                description={t(`admin.importer.source.${kind}.userHelp`)}
                autoComplete="off"
                value={user}
                onChange={(e) => {
                  setUser(e.currentTarget.value);
                  connect.reset();
                }}
              />
            )}
            <PasswordInput
              label={t('admin.importer.wizard.token')}
              required={source.tokenRequired}
              description={t(`admin.importer.source.${kind}.tokenHelp`)}
              autoComplete="off"
              value={token}
              onChange={(e) => {
                setToken(e.currentTarget.value);
                connect.reset();
              }}
            />
            {connect.isError && <ErrorAlert error={connect.error} />}
            {connect.data && (
              <Alert color="green" title={t('admin.importer.wizard.connected')}>
                {connect.data.login
                  ? t(`admin.importer.source.${kind}.connectedAs`, { version: connect.data.version, login: connect.data.login })
                  : t(`admin.importer.source.${kind}.connectedAnonymous`, { version: connect.data.version })}
              </Alert>
            )}
            <Group>
              <Button
                variant="default"
                loading={connect.isPending}
                onClick={() => connect.mutate()}
                disabled={!url || (source.tokenRequired && !token)}
              >
                {t('admin.importer.wizard.test')}
              </Button>
              <Button disabled={!connect.data} onClick={() => setStep(1)}>
                {t('admin.importer.next')}
              </Button>
            </Group>
          </Stack>
        </Stepper.Step>

        <Stepper.Step label={t(`admin.importer.source.${kind}.repository`, { defaultValue: t('admin.importer.wizard.step.repository') })}>
          <SimpleGrid cols={{ base: 1, md: 2 }} spacing="xl" mt="md">
            <Stack>
              <Title order={5}>{t(`admin.importer.source.${kind}.repository`, { defaultValue: t('admin.importer.wizard.repository') })}</Title>
              <Group align="end" wrap="nowrap">
                <TextInput
                  style={{ flex: 1 }}
                  label={t('admin.importer.wizard.search')}
                  description={t(`admin.importer.source.${kind}.searchHelp`)}
                  value={query}
                  onChange={(e) => setQuery(e.currentTarget.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') search.mutate();
                  }}
                />
                <Button variant="default" loading={search.isPending} onClick={() => search.mutate()}>
                  {t('admin.importer.wizard.find')}
                </Button>
              </Group>
              {search.isError && <ErrorAlert error={search.error} />}
              {search.data && search.data.length === 0 && (
                <Text size="sm" c="dimmed">
                  {t('common.nothingFound')}
                </Text>
              )}
              <Stack gap={6}>
                {search.data?.map((r) => (
                  <Paper
                    key={r.fullName}
                    withBorder
                    p="xs"
                    style={{ cursor: r.hasIssues ? 'pointer' : 'not-allowed', opacity: r.hasIssues ? 1 : 0.5 }}
                    bg={repository === r.fullName ? 'var(--mantine-primary-color-light)' : undefined}
                    onClick={() => r.hasIssues && chooseRepository(r)}
                  >
                    <Group justify="space-between" wrap="nowrap">
                      <Text size="sm" fw={500}>
                        {r.fullName}
                      </Text>
                      <Group gap={4}>
                        {r.isPrivate && <Badge variant="light">{t('admin.importer.wizard.private')}</Badge>}
                        {r.hasIssues ? (
                          r.openIssues >= 0 && (
                            <Badge variant="outline">{t('admin.importer.wizard.openIssues', { count: r.openIssues })}</Badge>
                          )
                        ) : (
                          <Badge color="gray">{t('admin.importer.wizard.noIssues')}</Badge>
                        )}
                      </Group>
                    </Group>
                    {r.description && (
                      <Text size="xs" c="dimmed" lineClamp={2}>
                        {r.description}
                      </Text>
                    )}
                  </Paper>
                ))}
              </Stack>
            </Stack>
            <Stack>
              <Title order={5}>{t('admin.importer.wizard.target')}</Title>
              <SegmentedControl
                value={target}
                onChange={(v) => setTarget(v as 'new' | 'existing')}
                data={[
                  { value: 'new', label: t('admin.importer.wizard.newProject') },
                  { value: 'existing', label: t('admin.importer.wizard.existingProject') },
                ]}
              />
              {target === 'new' ? (
                <>
                  <TextInput
                    label={t('admin.importer.wizard.projectName')}
                    required
                    value={newName}
                    onChange={(e) => setNewName(e.currentTarget.value)}
                  />
                  <TextInput
                    label={t('admin.importer.wizard.projectKey')}
                    required
                    value={newKey}
                    onChange={(e) => setNewKey(e.currentTarget.value.toUpperCase())}
                    error={newKey && !keyValid ? t('validation.projectKey') : undefined}
                  />
                </>
              ) : (
                <Select
                  label={t('admin.importer.project')}
                  searchable
                  data={projects.map((p) => ({ value: String(p.id), label: `${p.name} (${p.key})` }))}
                  value={projectId}
                  onChange={setProjectId}
                />
              )}
              <Text size="sm" c="dimmed">
                {t('admin.importer.wizard.numbering')}
              </Text>
            </Stack>
          </SimpleGrid>
          <Group mt="xl">
            <Button variant="default" onClick={() => setStep(0)}>
              {t('admin.importer.previous')}
            </Button>
            <Button
              disabled={!repository || !targetValid}
              loading={analysis.isPending}
              onClick={() => analysis.mutate(undefined, { onSuccess: () => setStep(2) })}
            >
              {t('admin.importer.next')}
            </Button>
            {repository && (
              <Text size="sm">{t('admin.importer.wizard.chosen', { repository })}</Text>
            )}
          </Group>
          {analysis.isError && <ErrorAlert error={analysis.error} mt="md" />}
        </Stepper.Step>

        <Stepper.Step label={t('admin.importer.wizard.step.mapping')}>
          {analysis.data && (
            <Stack mt="md" gap="xl">
              <Group gap="xl">
                <Text size="sm">
                  {t('admin.importer.wizard.found', {
                    issues: analysis.data.issues,
                    open: analysis.data.openIssues,
                    closed: analysis.data.closedIssues,
                    comments: analysis.data.comments,
                    milestones: analysis.data.milestones,
                  })}
                </Text>
              </Group>
              {analysis.data.alreadyImported > 0 && (
                <Alert color="blue">
                  {t('admin.importer.wizard.alreadyImported', { count: analysis.data.alreadyImported })}
                </Alert>
              )}
              <Select
                maw={360}
                label={t('admin.importer.wizard.issueType')}
                description={t('admin.importer.wizard.issueTypeHelp')}
                required
                data={issueTypes.map((type) => ({ value: String(type.id), label: type.name }))}
                value={issueTypeId}
                onChange={setIssueTypeId}
              />
              <Stack gap="xs">
                <Title order={5}>{t('admin.importer.wizard.labels')}</Title>
                <Text size="sm" c="dimmed">
                  {t('admin.importer.wizard.labelsHelp')}
                </Text>
                {analysis.data.labels.length === 0 ? (
                  <Text size="sm" c="dimmed">
                    {t('admin.importer.wizard.noLabels')}
                  </Text>
                ) : (
                  <Table maw={760}>
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th>{t('admin.importer.wizard.label')}</Table.Th>
                        <Table.Th>{t('admin.importer.wizard.used')}</Table.Th>
                        <Table.Th>{t('admin.importer.wizard.becomes')}</Table.Th>
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {analysis.data.labels.map((l) => (
                        <Table.Tr key={l.name}>
                          <Table.Td>
                            <Group gap={6} wrap="nowrap">
                              {l.color && <ColorSwatch color={`#${l.color}`} size={12} />}
                              <Text size="sm">{l.name}</Text>
                            </Group>
                          </Table.Td>
                          <Table.Td>
                            <Text size="sm">{l.issues}</Text>
                          </Table.Td>
                          <Table.Td>
                            <Select
                              size="xs"
                              data={ruleOptions}
                              value={ruleValue(rules[l.name] ?? l.suggestion)}
                              onChange={(v) => v && setRules((r) => ({ ...r, [l.name]: ruleOf(v) }))}
                              allowDeselect={false}
                            />
                          </Table.Td>
                        </Table.Tr>
                      ))}
                    </Table.Tbody>
                  </Table>
                )}
              </Stack>
              <Stack gap="xs">
                <Title order={5}>{t('admin.importer.wizard.users')}</Title>
                <Text size="sm" c="dimmed">
                  {t('admin.importer.wizard.usersHelp')}
                </Text>
                <Checkbox
                  label={t('admin.importer.wizard.matchUsernames')}
                  checked={matchUsernames}
                  onChange={(e) => setMatchUsernames(e.currentTarget.checked)}
                />
                <Table maw={760}>
                  <Table.Thead>
                    <Table.Tr>
                      <Table.Th>{t('admin.importer.wizard.sourceUser', { source: source.name })}</Table.Th>
                      <Table.Th>{t('admin.importer.wizard.used')}</Table.Th>
                      <Table.Th>{t('admin.importer.wizard.account')}</Table.Th>
                    </Table.Tr>
                  </Table.Thead>
                  <Table.Tbody>
                    {analysis.data.users.map((u) => (
                      <Table.Tr key={u.id}>
                        <Table.Td>
                          <Text size="sm">{u.login}</Text>
                          {u.name && (
                            <Text size="xs" c="dimmed">
                              {u.name}
                            </Text>
                          )}
                        </Table.Td>
                        <Table.Td>
                          <Text size="sm">{u.issues}</Text>
                        </Table.Td>
                        <Table.Td>
                          <AnyUserPicker
                            size="xs"
                            placeholder={t('admin.importer.wizard.automatic')}
                            value={users[u.id] ?? null}
                            onChange={(user) => setUsers((m) => ({ ...m, [u.id]: user }))}
                          />
                        </Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              </Stack>
              <Group>
                <Button variant="default" onClick={() => setStep(1)}>
                  {t('admin.importer.previous')}
                </Button>
                <Button disabled={!issueTypeId} onClick={() => setStep(3)}>
                  {t('admin.importer.next')}
                </Button>
              </Group>
            </Stack>
          )}
        </Stepper.Step>

        <Stepper.Step label={t('admin.importer.wizard.step.start')}>
          {analysis.data && (
            <Stack mt="md" maw={720}>
              <Text>
                {t('admin.importer.wizard.summary', {
                  issues: analysis.data.issues,
                  repository,
                  url,
                  project: target === 'new' ? `${newName} (${newKey})` : `${chosenProject?.name} (${chosenProject?.key})`,
                })}
              </Text>
              <List size="sm" spacing={4}>
                <List.Item>{t('admin.importer.wizard.willNumber')}</List.Item>
                <List.Item>{t('admin.importer.wizard.willHistory')}</List.Item>
                <List.Item>{t('admin.importer.wizard.willSubIssues')}</List.Item>
                <List.Item>{t('admin.importer.wizard.willPullRequests')}</List.Item>
                <List.Item>{t('admin.importer.wizard.willOnlyRead')}</List.Item>
                <List.Item>{t('admin.importer.wizard.willNotMail')}</List.Item>
                <List.Item>{t('admin.importer.wizard.willNotStoreToken')}</List.Item>
                {analysis.data.alreadyImported > 0 && (
                  <List.Item>{t('admin.importer.wizard.willSkip', { count: analysis.data.alreadyImported })}</List.Item>
                )}
              </List>
              {Object.keys(startErrors).length > 0 && (
                <Alert color="red">{Object.values(startErrors).join(' ')}</Alert>
              )}
              <Group>
                <Button variant="default" onClick={() => setStep(2)}>
                  {t('admin.importer.previous')}
                </Button>
                <Button loading={start.isPending} onClick={submit}>
                  {t('admin.importer.wizard.startImport')}
                </Button>
              </Group>
            </Stack>
          )}
        </Stepper.Step>
      </Stepper>
    </Stack>
  );
}
