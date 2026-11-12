// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import {
  ActionIcon,
  Anchor,
  Badge,
  Breadcrumbs,
  Button,
  Card,
  Grid,
  Group,
  Menu,
  Modal,
  Stack,
  Table,
  Tabs,
  Text,
  Title,
} from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';
import { IconDots, IconExternalLink, IconPencil, IconPlus, IconTrash } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router';
import { del, seg } from '../api/client';
import { useIssueMutations } from '../api/issueMutations';
import { useIssue, useIssueTypes, useProjectPermissions, useTransitions } from '../api/queries';
import type { IssueResponse, TransitionResponse } from '../api/types';
import { CreateIssueModal } from '../components/CreateIssueModal';
import { EditIssueModal } from '../components/EditIssueModal';
import { IssueAttachments } from '../components/IssueAttachments';
import { IssueComments } from '../components/IssueComments';
import { IssueHistory } from '../components/IssueHistory';
import { IssueLinks } from '../components/IssueLinks';
import { IssueTypeIcon, PriorityLabel, StatusBadge } from '../components/IssueIcons';
import { ErrorAlert, isNotFound, PageLoader } from '../components/QueryState';
import { TransitionModal } from '../components/TransitionModal';
import { UserLabel } from '../components/UserLabel';
import { UserPicker } from '../components/UserPicker';
import { WatchButton } from '../components/WatchButton';
import { formatDate, formatDateTime } from '../lib/format';
import { childTypes } from '../lib/issueTypes';
import { NotFoundPage } from './NotFoundPage';

export function IssuePage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const issueKey = useParams().issueKey!;
  const issue = useIssue(issueKey);

  // A former key still works; show the current one in the address bar.
  const currentKey = issue.data?.key;
  useEffect(() => {
    if (currentKey && currentKey !== issueKey) navigate(`/browse/${currentKey}`, { replace: true });
  }, [currentKey, issueKey, navigate]);

  if (issue.isPending) return <PageLoader />;
  if (issue.isError) {
    return isNotFound(issue.error) ? <NotFoundPage message={t('issue.notFound')} /> : <ErrorAlert error={issue.error} />;
  }
  return <IssueView issue={issue.data} />;
}

function IssueView({ issue }: { issue: IssueResponse }) {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const permissions = useProjectPermissions(issue.project.key).data ?? new Set();
  const canEdit = permissions.has('EDIT_ISSUE');
  const canTransition = permissions.has('TRANSITION_ISSUE');
  const transitions = useTransitions(issue.key, canTransition);
  const { update, transition } = useIssueMutations(issue);
  const issueTypes = useIssueTypes().data ?? [];
  const canHaveChildren = childTypes(issueTypes, issue.issueType.hierarchyLevel).length > 0;

  const [editing, editModal] = useDisclosure(false);
  const [creatingChild, childModal] = useDisclosure(false);
  const [deleting, deleteModal] = useDisclosure(false);
  const [askResolution, setAskResolution] = useState<TransitionResponse | null>(null);

  const remove = useMutation({
    mutationFn: () => del(`/api/v1/issues/${seg(issue.key)}`),
    onSuccess: () => {
      queryClient.removeQueries({ queryKey: ['issue', issue.key] });
      void queryClient.invalidateQueries({ queryKey: ['project', issue.project.key] });
      if (issue.parent) void queryClient.invalidateQueries({ queryKey: ['issue', issue.parent.key] });
      notifications.show({ color: 'green', message: t('issue.deleted', { key: issue.key }) });
      navigate(issue.parent ? `/browse/${issue.parent.key}` : `/projects/${issue.project.key}`, { replace: true });
    },
    onError: (e) => notifications.show({ color: 'red', title: t('error.title'), message: e.message }),
  });

  function runTransition(tr: TransitionResponse) {
    if (tr.setsResolution) setAskResolution(tr);
    else transition.mutate({ transitionId: tr.id });
  }

  const childLabel = issue.issueType.hierarchyLevel > 0 ? t('issue.addChildIssue') : t('issue.createSubtask');

  return (
    <Stack>
      <Breadcrumbs>
        <Anchor component={Link} to="/projects" size="sm">
          {t('projects.title')}
        </Anchor>
        <Anchor component={Link} to={`/projects/${issue.project.key}`} size="sm">
          {issue.project.name}
        </Anchor>
        {issue.parent && (
          <Anchor component={Link} to={`/browse/${issue.parent.key}`} size="sm">
            {issue.parent.key}
          </Anchor>
        )}
        <Group gap={6} wrap="nowrap">
          <IssueTypeIcon type={issue.issueType} size={16} />
          <Text size="sm">{issue.key}</Text>
        </Group>
      </Breadcrumbs>

      <Title order={2} className="bw-pre-wrap">
        {issue.summary}
      </Title>

      <Group gap="xs">
        {canEdit && (
          <Button variant="default" leftSection={<IconPencil size={16} />} onClick={editModal.open}>
            {t('common.edit')}
          </Button>
        )}
        {canHaveChildren && permissions.has('CREATE_ISSUE') && (
          <Button variant="default" leftSection={<IconPlus size={16} />} onClick={childModal.open}>
            {childLabel}
          </Button>
        )}
        {canTransition &&
          (transitions.data ?? []).map((tr) => (
            <Button
              key={tr.id}
              variant="light"
              loading={transition.isPending && transition.variables?.transitionId === tr.id}
              disabled={transition.isPending}
              onClick={() => runTransition(tr)}
            >
              {tr.name}
            </Button>
          ))}
        <WatchButton issueKey={issue.key} />
        {permissions.has('DELETE_ISSUE') && (
          <Menu position="bottom-end">
            <Menu.Target>
              <ActionIcon variant="default" size="lg" aria-label={t('common.more')}>
                <IconDots size={16} />
              </ActionIcon>
            </Menu.Target>
            <Menu.Dropdown>
              <Menu.Item color="red" leftSection={<IconTrash size={16} />} onClick={deleteModal.open}>
                {t('common.delete')}
              </Menu.Item>
            </Menu.Dropdown>
          </Menu>
        )}
      </Group>

      <Grid gap="xl">
        <Grid.Col span={{ base: 12, md: 8 }}>
          <Stack gap="xl">
            <Section title={t('issue.description')}>
              {issue.description ? (
                <Text className="bw-pre-wrap">{issue.description}</Text>
              ) : (
                <Text c="dimmed" size="sm">
                  {t('issue.noDescription')}
                </Text>
              )}
            </Section>

            {issue.children.length > 0 && (
              <Section title={issue.issueType.hierarchyLevel > 0 ? t('issue.issuesInEpic') : t('issue.subtasks')}>
                <Table verticalSpacing={6}>
                  <Table.Tbody>
                    {issue.children.map((child) => (
                      <Table.Tr key={child.id}>
                        <Table.Td w={30}>
                          <IssueTypeIcon type={child.issueType} size={16} />
                        </Table.Td>
                        <Table.Td w={100}>
                          <Anchor component={Link} to={`/browse/${child.key}`} size="sm">
                            {child.key}
                          </Anchor>
                        </Table.Td>
                        <Table.Td>
                          <Text size="sm">{child.summary}</Text>
                        </Table.Td>
                        <Table.Td w={130}>
                          <StatusBadge status={child.status} size="xs" />
                        </Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              </Section>
            )}

            <IssueAttachments issueKey={issue.key} canUpload={permissions.has('CREATE_ATTACHMENT')} />

            <IssueLinks issueKey={issue.key} canLink={permissions.has('LINK_ISSUE')} />

            {issue.remoteLinks.length > 0 && (
              <Section title={t('issue.remoteLinks')}>
                <Stack gap={4}>
                  {issue.remoteLinks.map((link) => (
                    <Group key={link.id} gap={6} wrap="nowrap">
                      <IconExternalLink size={14} />
                      <Text size="xs" c="dimmed" w={110}>
                        {t(`issue.remoteLink.${link.relation}`)}
                      </Text>
                      <Anchor href={link.url} target="_blank" rel="noopener noreferrer" size="sm" lineClamp={1}>
                        {link.title}
                      </Anchor>
                    </Group>
                  ))}
                </Stack>
              </Section>
            )}

            <Tabs defaultValue="comments" keepMounted={false}>
              <Tabs.List>
                <Tabs.Tab value="comments">{t('comment.title')}</Tabs.Tab>
                <Tabs.Tab value="history">{t('history.title')}</Tabs.Tab>
              </Tabs.List>
              <Tabs.Panel value="comments" pt="md">
                <IssueComments issueKey={issue.key} canAdd={permissions.has('ADD_COMMENT')} />
              </Tabs.Panel>
              <Tabs.Panel value="history" pt="md">
                <IssueHistory issueKey={issue.key} />
              </Tabs.Panel>
            </Tabs>
          </Stack>
        </Grid.Col>

        <Grid.Col span={{ base: 12, md: 4 }}>
          <Card withBorder padding="md">
            <Stack gap="sm">
              <Field label={t('issue.status')}>
                <Group gap={6}>
                  <StatusBadge status={issue.status} size="md" />
                  {issue.resolution && <Text size="sm">({issue.resolution.name})</Text>}
                </Group>
              </Field>
              <Field label={t('issue.type')}>
                <Group gap={6} wrap="nowrap">
                  <IssueTypeIcon type={issue.issueType} size={16} />
                  <Text size="sm">{issue.issueType.name}</Text>
                </Group>
              </Field>
              <Field label={t('issue.priority')}>
                <PriorityLabel priority={issue.priority} />
              </Field>
              <Field label={t('issue.assignee')}>
                {permissions.has('ASSIGN_ISSUE') ? (
                  <UserPicker
                    projectKey={issue.project.key}
                    value={issue.assignee}
                    disabled={update.isPending}
                    size="xs"
                    aria-label={t('issue.assignee')}
                    onChange={(user) =>
                      update.mutate(user ? { assigneeId: user.id } : { unassign: true })
                    }
                  />
                ) : (
                  <UserLabel user={issue.assignee} />
                )}
              </Field>
              <Field label={t('issue.reporter')}>
                <UserLabel user={issue.reporter} emptyLabel="—" />
              </Field>
              {issue.labels.length > 0 && (
                <Field label={t('issue.labels')}>
                  <Group gap={4}>
                    {issue.labels.map((l) => (
                      <Badge key={l} variant="outline" color="gray" radius="sm" tt="none">
                        {l}
                      </Badge>
                    ))}
                  </Group>
                </Field>
              )}
              {issue.components.length > 0 && (
                <Field label={t('issue.components')}>
                  <Text size="sm">{issue.components.map((c) => c.name).join(', ')}</Text>
                </Field>
              )}
              {issue.affectsVersions.length > 0 && (
                <Field label={t('issue.affectsVersions')}>
                  <Text size="sm">{issue.affectsVersions.map((v) => v.name).join(', ')}</Text>
                </Field>
              )}
              {issue.fixVersions.length > 0 && (
                <Field label={t('issue.fixVersions')}>
                  <Text size="sm">{issue.fixVersions.map((v) => v.name).join(', ')}</Text>
                </Field>
              )}
              {issue.dueDate && (
                <Field label={t('issue.dueDate')}>
                  <Text size="sm">{formatDate(issue.dueDate, i18n.language)}</Text>
                </Field>
              )}
              <Field label={t('issue.created')}>
                <Text size="sm">{formatDateTime(issue.createdAt, i18n.language)}</Text>
              </Field>
              <Field label={t('issue.updated')}>
                <Text size="sm">{formatDateTime(issue.updatedAt, i18n.language)}</Text>
              </Field>
              {issue.resolvedAt && (
                <Field label={t('issue.resolved')}>
                  <Text size="sm">{formatDateTime(issue.resolvedAt, i18n.language)}</Text>
                </Field>
              )}
              {issue.origin && (
                <Field label={t('issue.origin')}>
                  {issue.origin.url ? (
                    <Anchor href={issue.origin.url} target="_blank" rel="noopener noreferrer" size="sm">
                      {t('issue.originValue', {
                        source: t(`issue.originKind.${issue.origin.kind}`, { defaultValue: issue.origin.kind }),
                        key: issue.origin.key,
                      })}
                    </Anchor>
                  ) : (
                    <Text size="sm">{issue.origin.key}</Text>
                  )}
                </Field>
              )}
            </Stack>
          </Card>
        </Grid.Col>
      </Grid>

      {canEdit && <EditIssueModal issue={issue} opened={editing} onClose={editModal.close} />}
      <CreateIssueModal
        projectKey={issue.project.key}
        parent={{ key: issue.key, hierarchyLevel: issue.issueType.hierarchyLevel }}
        opened={creatingChild}
        onClose={childModal.close}
      />
      <TransitionModal
        transition={askResolution}
        currentResolutionId={issue.resolution?.id ?? null}
        loading={transition.isPending}
        onClose={() => setAskResolution(null)}
        onConfirm={(resolutionId) =>
          transition.mutate(
            { transitionId: askResolution!.id, resolutionId },
            { onSuccess: () => setAskResolution(null) },
          )
        }
      />
      <Modal opened={deleting} onClose={deleteModal.close} title={t('issue.deleteTitle', { key: issue.key })}>
        <Stack>
          <Text size="sm">
            {issue.children.length === 0
              ? t('issue.deleteConfirm')
              : issue.issueType.hierarchyLevel > 0
                ? t('issue.deleteConfirmEpic', { count: issue.children.length })
                : t('issue.deleteConfirmWithSubtasks', { count: issue.children.length })}
          </Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={deleteModal.close}>
              {t('common.cancel')}
            </Button>
            <Button color="red" loading={remove.isPending} onClick={() => remove.mutate()}>
              {t('common.delete')}
            </Button>
          </Group>
        </Stack>
      </Modal>
    </Stack>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Stack gap="xs">
      <Title order={4}>{title}</Title>
      {children}
    </Stack>
  );
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <Grid gap="xs" align="center">
      <Grid.Col span={4}>
        <Text size="sm" c="dimmed">
          {label}
        </Text>
      </Grid.Col>
      <Grid.Col span={8}>{children}</Grid.Col>
    </Grid>
  );
}
