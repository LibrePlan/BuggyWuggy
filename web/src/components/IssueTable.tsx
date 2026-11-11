// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Anchor, Table, Text } from '@mantine/core';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router';
import type { IssueSummaryResponse } from '../api/types';
import { formatDateTime } from '../lib/format';
import { IssueTypeIcon, PriorityIcon, StatusBadge } from './IssueIcons';
import { UserLabel } from './UserLabel';

/** A list of issues; rows open the issue. */
export function IssueTable({ issues, showProject = false }: { issues: IssueSummaryResponse[]; showProject?: boolean }) {
  const { t, i18n } = useTranslation();
  const navigate = useNavigate();
  return (
    <Table.ScrollContainer minWidth={showProject ? 820 : 680}>
      <Table highlightOnHover verticalSpacing="xs">
        <Table.Thead>
          <Table.Tr>
            <Table.Th w={36} aria-label={t('issue.type')} />
            <Table.Th w={110}>{t('issue.key')}</Table.Th>
            <Table.Th>{t('issue.summary')}</Table.Th>
            {showProject && <Table.Th w={160}>{t('issue.project')}</Table.Th>}
            <Table.Th w={150}>{t('issue.status')}</Table.Th>
            <Table.Th w={36} aria-label={t('issue.priority')} />
            <Table.Th w={180}>{t('issue.assignee')}</Table.Th>
            <Table.Th w={170}>{t('issue.updated')}</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {issues.map((issue) => (
            <Table.Tr key={issue.id} className="bw-row-link" onClick={() => navigate(`/browse/${issue.key}`)}>
              <Table.Td>
                <IssueTypeIcon type={issue.issueType} />
              </Table.Td>
              <Table.Td>
                <Anchor
                  component={Link}
                  to={`/browse/${issue.key}`}
                  size="sm"
                  td={issue.resolution ? 'line-through' : undefined}
                  onClick={(e) => e.stopPropagation()}
                >
                  {issue.key}
                </Anchor>
              </Table.Td>
              <Table.Td>
                <Text size="sm" lineClamp={2}>
                  {issue.summary}
                </Text>
              </Table.Td>
              {showProject && (
                <Table.Td>
                  <Text size="sm" truncate>
                    {issue.project.name}
                  </Text>
                </Table.Td>
              )}
              <Table.Td>
                <StatusBadge status={issue.status} />
              </Table.Td>
              <Table.Td>{issue.priority && <PriorityIcon priority={issue.priority} />}</Table.Td>
              <Table.Td>
                <UserLabel user={issue.assignee} />
              </Table.Td>
              <Table.Td>
                <Text size="sm" c="dimmed">
                  {formatDateTime(issue.updatedAt, i18n.language)}
                </Text>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Table.ScrollContainer>
  );
}
