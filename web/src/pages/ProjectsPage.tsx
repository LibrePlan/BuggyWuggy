// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Anchor, Badge, Button, Group, Pagination, Stack, Switch, Table, Text, TextInput, Title } from '@mantine/core';
import { useDebouncedValue, useDisclosure } from '@mantine/hooks';
import { IconPlus, IconSearch } from '@tabler/icons-react';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import { useMyself, useProjects } from '../api/queries';
import { CreateProjectModal } from '../components/CreateProjectModal';
import { ErrorAlert, PageLoader } from '../components/QueryState';
import { UserLabel } from '../components/UserLabel';

export function ProjectsPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const myself = useMyself().data;
  const [search, setSearch] = useState('');
  const [query] = useDebouncedValue(search.trim(), 250);
  const [page, setPage] = useState(0);
  const isAdmin = myself?.globalPermissions.includes('ADMINISTER') ?? false;
  const [showArchived, setShowArchived] = useState(false);
  const projects = useProjects(query, page, isAdmin && showArchived);
  const [creating, createModal] = useDisclosure(false);
  const canCreate = myself?.globalPermissions.includes('CREATE_PROJECT') ?? false;

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>{t('projects.title')}</Title>
        {canCreate && (
          <Button leftSection={<IconPlus size={16} />} onClick={createModal.open}>
            {t('projects.create')}
          </Button>
        )}
      </Group>
      <TextInput
        placeholder={t('projects.search')}
        leftSection={<IconSearch size={16} />}
        value={search}
        onChange={(e) => {
          setSearch(e.currentTarget.value);
          setPage(0);
        }}
        maw={360}
      />
      {isAdmin && (
        <Switch
          label={t('projects.showArchived')}
          checked={showArchived}
          onChange={(e) => {
            setShowArchived(e.currentTarget.checked);
            setPage(0);
          }}
        />
      )}
      {projects.isPending ? (
        <PageLoader />
      ) : projects.isError ? (
        <ErrorAlert error={projects.error} />
      ) : projects.data.items.length === 0 ? (
        <Text c="dimmed">{query ? t('common.nothingFound') : t('projects.empty')}</Text>
      ) : (
        <>
          <Table highlightOnHover verticalSpacing="sm">
            <Table.Thead>
              <Table.Tr>
                <Table.Th>{t('project.name')}</Table.Th>
                <Table.Th>{t('project.key')}</Table.Th>
                <Table.Th visibleFrom="sm">{t('project.lead')}</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {projects.data.items.map((p) => (
                <Table.Tr key={p.id} className="bw-row-link" onClick={() => navigate(`/projects/${p.key}`)}>
                  <Table.Td>
                    <Anchor component={Link} to={`/projects/${p.key}`} fw={500} onClick={(e) => e.stopPropagation()}>
                      {p.name}
                    </Anchor>
                    {p.archived && (
                      <Badge ml="xs" size="xs" color="gray" variant="light">
                        {t('project.archived')}
                      </Badge>
                    )}
                  </Table.Td>
                  <Table.Td>{p.key}</Table.Td>
                  <Table.Td visibleFrom="sm">
                    <UserLabel user={p.lead} emptyLabel="—" />
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
          {projects.data.total > projects.data.size && (
            <Pagination
              total={Math.ceil(projects.data.total / projects.data.size)}
              value={page + 1}
              onChange={(p) => setPage(p - 1)}
            />
          )}
        </>
      )}
      <CreateProjectModal opened={creating} onClose={createModal.close} />
    </Stack>
  );
}
