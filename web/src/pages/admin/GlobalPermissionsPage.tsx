// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Alert, Badge, CloseButton, Group, Stack, Table, Text } from '@mantine/core';
import { IconInfoCircle } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { del, put } from '../../api/client';
import { keys, useGlobalPermissions } from '../../api/queries';
import type { GroupRef } from '../../api/types';
import { GroupPicker } from '../../components/EntityPickers';
import { ErrorAlert, PageLoader } from '../../components/QueryState';
import { notifyError } from '../../lib/notify';

export function GlobalPermissionsPage() {
  const { t } = useTranslation();
  const permissions = useGlobalPermissions();
  const queryClient = useQueryClient();
  const change = useMutation({
    mutationFn: ({ permission, group, grant }: { permission: string; group: GroupRef; grant: boolean }) => {
      const path = `/api/v1/admin/global-permissions/${permission}/groups/${group.id}`;
      return grant ? put(path) : del(path);
    },
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: keys.globalPermissions }),
    onError: notifyError,
  });

  if (permissions.isPending) return <PageLoader />;
  if (permissions.isError) return <ErrorAlert error={permissions.error} />;

  return (
    <Stack>
      <Alert icon={<IconInfoCircle />} color="blue" variant="light">
        {t('admin.permissions.intro')}
      </Alert>
      <Table verticalSpacing="md">
        <Table.Thead>
          <Table.Tr>
            <Table.Th w="40%">{t('admin.permissions.permission')}</Table.Th>
            <Table.Th>{t('admin.permissions.groups')}</Table.Th>
          </Table.Tr>
        </Table.Thead>
        <Table.Tbody>
          {permissions.data.map((p) => (
            <Table.Tr key={p.permission}>
              <Table.Td valign="top">
                <Text fw={500}>{t(`admin.permissions.name.${p.permission}`, { defaultValue: p.permission })}</Text>
                <Text size="sm" c="dimmed">
                  {t(`admin.permissions.help.${p.permission}`, { defaultValue: '' })}
                </Text>
              </Table.Td>
              <Table.Td valign="top">
                <Stack gap="xs">
                  <Group gap={6}>
                    {p.groups.length === 0 && (
                      <Text size="sm" c="dimmed">
                        {t('admin.permissions.none')}
                      </Text>
                    )}
                    {p.groups.map((g) => (
                      <Badge
                        key={g.id}
                        variant="light"
                        size="lg"
                        tt="none"
                        pr={4}
                        rightSection={
                          <CloseButton
                            size="xs"
                            aria-label={t('admin.permissions.revoke', { group: g.name })}
                            title={t('admin.permissions.revoke', { group: g.name })}
                            onClick={() => change.mutate({ permission: p.permission, group: g, grant: false })}
                          />
                        }
                      >
                        <Link to={`/admin/groups/${g.id}`} className="bw-plain-link">
                          {g.name}
                        </Link>
                      </Badge>
                    ))}
                  </Group>
                  <GroupPicker
                    size="xs"
                    maw={260}
                    placeholder={t('admin.permissions.grant')}
                    aria-label={t('admin.permissions.grant')}
                    exclude={p.groups.map((g) => g.id)}
                    onChange={(group) => group && change.mutate({ permission: p.permission, group, grant: true })}
                  />
                </Stack>
              </Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Stack>
  );
}
