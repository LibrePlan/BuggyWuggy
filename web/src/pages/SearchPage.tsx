// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { ActionIcon, Badge, Button, Group, Menu, Modal, Pagination, Select, Stack, Text, TextInput, Title } from '@mantine/core';
import { useForm } from '@mantine/form';
import { useDisclosure } from '@mantine/hooks';
import { notifications } from '@mantine/notifications';
import { IconDeviceFloppy, IconDots, IconPencil, IconTrash } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate, useSearchParams } from 'react-router';
import { del, patch } from '../api/client';
import { keys, useAllProjects, useFilter, useIssueSearch } from '../api/queries';
import type { FilterResponse, SharedWith, UpdateFilterRequest } from '../api/types';
import { FilterBar } from '../components/FilterBar';
import { FiltersMenu } from '../components/FiltersMenu';
import { IssueTable } from '../components/IssueTable';
import { ErrorAlert, PageLoader } from '../components/QueryState';
import { SaveFilterModal, ShareSelect } from '../components/SaveFilterModal';
import { fieldErrorMap } from '../lib/apiError';
import { normalizeQuery, paramsToQuery, queryToParams, sameQuery, type IssueQuery } from '../lib/issueQuery';

const PAGE_SIZE = 50;

/** The views of the issue list: the plain list, and TaskWarrior's (with urgency, most urgent first). */
export type IssueView = 'issues' | 'taskwarrior';

const VIEW_PATH: Record<IssueView, string> = { issues: '/issues', taskwarrior: '/issues/taskwarrior' };

/**
 * Issue search with filters in the URL (/issues?q=…&status=…), and saved filters (&filter=ID).
 * The TaskWarrior view (/issues/taskwarrior) shows the urgency and sorts by it unless another
 * sort is chosen.
 */
export function SearchPage({ view = 'issues' }: { view?: IssueView }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [params, setParams] = useSearchParams();
  const query = useMemo(() => paramsToQuery(params), [params]);
  const page = Math.max(0, Number(params.get('page') ?? '1') - 1) || 0;
  const filterId = /^\d+$/.test(params.get('filter') ?? '') ? Number(params.get('filter')) : null;
  const filter = useFilter(filterId);
  const taskwarrior = view === 'taskwarrior';
  const results = useIssueSearch(
    normalizeQuery(taskwarrior ? { ...query, sort: query.sort ?? 'URGENCY' } : query),
    page,
    PAGE_SIZE,
  );
  const [saving, saveModal] = useDisclosure(false);
  const [editing, editModal] = useDisclosure(false);

  const changed = filter.data ? !sameQuery(filter.data.query, query) : false;

  function show(next: IssueQuery, nextFilterId: number | null = filterId, nextPage = 0) {
    const p = queryToParams(next);
    if (nextFilterId !== null) p.set('filter', String(nextFilterId));
    if (nextPage > 0) p.set('page', String(nextPage + 1));
    setParams(p);
  }

  function open(f: FilterResponse) {
    show(f.query, f.id);
  }

  const update = useMutation({
    mutationFn: (request: UpdateFilterRequest) => patch<FilterResponse>(`/api/v1/filters/${filterId}`, request),
    onSuccess: (f) => {
      queryClient.setQueryData(keys.filter(f.id), f);
      void queryClient.invalidateQueries({ queryKey: keys.filters, exact: true });
      notifications.show({ color: 'green', message: t('filter.saved', { name: f.name }) });
    },
    onError: (e) => notifications.show({ color: 'red', title: t('error.title'), message: e.message }),
  });

  const remove = useMutation({
    mutationFn: () => del(`/api/v1/filters/${filterId}`),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: keys.filters });
      notifications.show({ color: 'green', message: t('filter.deleted', { name: filter.data?.name }) });
      show(query, null);
    },
    onError: (e) => notifications.show({ color: 'red', title: t('error.title'), message: e.message }),
  });

  const data = results.data;
  return (
    <Stack>
      <Group justify="space-between" align="flex-start">
        <Group gap="sm" align="center">
          <Title order={2}>{filter.data?.name ?? t(taskwarrior ? 'taskwarrior.title' : 'search.title')}</Title>
          {filter.data && changed && (
            <Badge color="orange" variant="light">
              {t('filter.modified')}
            </Badge>
          )}
          {filter.data?.mine && (
            <Menu position="bottom-start" withinPortal>
              <Menu.Target>
                <ActionIcon variant="subtle" color="gray" aria-label={t('common.more')}>
                  <IconDots size={16} />
                </ActionIcon>
              </Menu.Target>
              <Menu.Dropdown>
                <Menu.Item leftSection={<IconPencil size={16} />} onClick={editModal.open}>
                  {t('filter.edit')}
                </Menu.Item>
                <Menu.Item
                  color="red"
                  leftSection={<IconTrash size={16} />}
                  onClick={() => {
                    if (window.confirm(t('filter.deleteConfirm', { name: filter.data!.name }))) remove.mutate();
                  }}
                >
                  {t('filter.delete')}
                </Menu.Item>
              </Menu.Dropdown>
            </Menu>
          )}
        </Group>
        <Group gap="xs">
          <FiltersMenu onSelect={open} />
          {filter.data?.mine && changed && (
            <>
              <Button variant="subtle" color="gray" onClick={() => show(filter.data!.query)}>
                {t('filter.discard')}
              </Button>
              <Button
                leftSection={<IconDeviceFloppy size={16} />}
                loading={update.isPending}
                onClick={() => update.mutate({ query: normalizeQuery(query) })}
              >
                {t('filter.saveChanges')}
              </Button>
            </>
          )}
          <Button variant={filter.data?.mine && changed ? 'default' : 'filled'} onClick={saveModal.open}>
            {filter.data ? t('filter.saveAs') : t('filter.save')}
          </Button>
        </Group>
      </Group>
      {filter.data?.description && (
        <Text size="sm" c="dimmed" className="bw-pre-wrap">
          {filter.data.description}
        </Text>
      )}
      {filter.isError && <ErrorAlert error={filter.error} />}

      {taskwarrior && (
        <Text size="sm" c="dimmed">
          {t('taskwarrior.intro')}
        </Text>
      )}
      <FilterBar query={query} onChange={(q) => show(q)} defaultSort={taskwarrior ? 'URGENCY' : undefined} />

      {results.isPending ? (
        <PageLoader />
      ) : results.isError ? (
        <ErrorAlert error={results.error} />
      ) : data!.items.length === 0 ? (
        <Text c="dimmed" py="md">
          {t('search.noResults')}
        </Text>
      ) : (
        <>
          <IssueTable issues={data!.items} showProject showUrgency={taskwarrior} />
          <Group justify="space-between">
            <Text size="sm" c="dimmed">
              {t('project.issueCount', { count: data!.total })}
            </Text>
            {data!.total > data!.size && (
              <Pagination
                total={Math.ceil(data!.total / data!.size)}
                value={page + 1}
                onChange={(n) => show(query, filterId, n - 1)}
              />
            )}
          </Group>
        </>
      )}

      <SaveFilterModal
        query={query}
        opened={saving}
        onClose={saveModal.close}
        onSaved={(f) => {
          notifications.show({ color: 'green', message: t('filter.saved', { name: f.name }) });
          navigate(`${VIEW_PATH[view]}?${new URLSearchParams([...queryToParams(f.query), ['filter', String(f.id)]])}`);
        }}
      />
      {filter.data?.mine && (
        <EditFilterModal
          key={`${filter.data.id}-${filter.data.updatedAt}`}
          filter={filter.data}
          opened={editing}
          onClose={editModal.close}
        />
      )}
    </Stack>
  );
}

/** Name, description and sharing of an own filter. */
function EditFilterModal({ filter, opened, onClose }: { filter: FilterResponse; opened: boolean; onClose: () => void }) {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const projects = useAllProjects().data ?? [];
  const form = useForm({
    initialValues: {
      name: filter.name,
      description: filter.description ?? '',
      sharedWith: filter.sharedWith as SharedWith,
      projectId: filter.project ? String(filter.project.id) : (null as string | null),
    },
    validate: {
      name: (v) => (v.trim() ? null : t('validation.required')),
      projectId: (v, values) => (values.sharedWith === 'PROJECT' && !v ? t('validation.required') : null),
    },
  });
  const save = useMutation({
    mutationFn: (request: UpdateFilterRequest) => patch<FilterResponse>(`/api/v1/filters/${filter.id}`, request),
    onSuccess: (f) => {
      queryClient.setQueryData(keys.filter(f.id), f);
      void queryClient.invalidateQueries({ queryKey: keys.filters, exact: true });
      onClose();
    },
    onError: (e) => form.setErrors({ ...fieldErrorMap(e), name: fieldErrorMap(e).name ?? e.message }),
  });

  return (
    <Modal opened={opened} onClose={onClose} title={t('filter.edit')}>
      <form
        onSubmit={form.onSubmit((v) =>
          save.mutate({
            name: v.name.trim(),
            description: v.description,
            sharedWith: v.sharedWith,
            projectId: v.sharedWith === 'PROJECT' && v.projectId ? Number(v.projectId) : undefined,
          }),
        )}
      >
        <Stack>
          <TextInput label={t('filter.name')} required maxLength={255} {...form.getInputProps('name')} />
          <TextInput label={t('project.description')} {...form.getInputProps('description')} />
          <ShareSelect value={form.values.sharedWith} onChange={(v) => form.setFieldValue('sharedWith', v)} />
          {form.values.sharedWith === 'PROJECT' && (
            <Select
              label={t('issue.project')}
              required
              searchable
              data={projects.map((p) => ({ value: String(p.id), label: p.name }))}
              {...form.getInputProps('projectId')}
            />
          )}
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button type="submit" loading={save.isPending}>
              {t('common.save')}
            </Button>
          </Group>
        </Stack>
      </form>
    </Modal>
  );
}
