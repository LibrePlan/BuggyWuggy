// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Badge, CloseButton, Group, Paper, SimpleGrid, Stack, Text, Title } from '@mantine/core';
import { IconUser, IconUsersGroup } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { del, put, seg } from '../../api/client';
import { keys, useProjectRoles } from '../../api/queries';
import type { ProjectRoleMembers } from '../../api/types';
import { notifyError } from '../../lib/notify';
import { AnyUserPicker, GroupPicker } from '../EntityPickers';
import { ErrorAlert, PageLoader } from '../QueryState';

type Change = { role: ProjectRoleMembers; kind: 'users' | 'groups'; id: number; add: boolean };

/** Who has which role in the project; the permission scheme decides what each role may do. */
export function RolesSettings({ projectKey, readOnly }: { projectKey: string; readOnly: boolean }) {
  const { t } = useTranslation();
  const roles = useProjectRoles(projectKey);
  const queryClient = useQueryClient();
  const change = useMutation({
    mutationFn: ({ role, kind, id, add }: Change) => {
      const path = `/api/v1/projects/${seg(projectKey)}/roles/${role.id}/${kind}/${id}`;
      return add ? put(path) : del(path);
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: keys.projectRoles(projectKey) });
      // Roles decide permissions, also those of the current user.
      void queryClient.invalidateQueries({ queryKey: ['project', projectKey] });
    },
    onError: notifyError,
  });

  if (roles.isPending) return <PageLoader />;
  if (roles.isError) return <ErrorAlert error={roles.error} />;

  return (
    <Stack>
      <Text size="sm" c="dimmed">
        {t('settings.rolesIntro')}
      </Text>
      <SimpleGrid cols={{ base: 1, md: 3 }}>
        {roles.data.map((role) => (
          <Paper key={role.id} withBorder p="md">
            <Stack gap="sm">
              <div>
                <Title order={5}>{role.name}</Title>
                {role.description && (
                  <Text size="sm" c="dimmed">
                    {role.description}
                  </Text>
                )}
              </div>
              <Group gap={6}>
                {role.groups.length === 0 && role.users.length === 0 && (
                  <Text size="sm" c="dimmed">
                    {t('settings.role.empty')}
                  </Text>
                )}
                {role.groups.map((g) => (
                  <Member
                    key={`g${g.id}`}
                    icon={<IconUsersGroup size={12} />}
                    label={g.name}
                    readOnly={readOnly}
                    onRemove={() => change.mutate({ role, kind: 'groups', id: g.id, add: false })}
                  />
                ))}
                {role.users.map((u) => (
                  <Member
                    key={`u${u.id}`}
                    icon={<IconUser size={12} />}
                    label={u.displayName}
                    readOnly={readOnly}
                    onRemove={() => change.mutate({ role, kind: 'users', id: u.id, add: false })}
                  />
                ))}
              </Group>
              {!readOnly && (
                <>
                  <GroupPicker
                    size="xs"
                    placeholder={t('settings.role.addGroup')}
                    aria-label={t('settings.role.addGroup')}
                    exclude={role.groups.map((g) => g.id)}
                    onChange={(g) => g && change.mutate({ role, kind: 'groups', id: g.id, add: true })}
                  />
                  <AnyUserPicker
                    size="xs"
                    placeholder={t('settings.role.addUser')}
                    aria-label={t('settings.role.addUser')}
                    exclude={role.users.map((u) => u.id)}
                    onChange={(u) => u && change.mutate({ role, kind: 'users', id: u.id, add: true })}
                  />
                </>
              )}
            </Stack>
          </Paper>
        ))}
      </SimpleGrid>
    </Stack>
  );
}

function Member({
  icon,
  label,
  readOnly,
  onRemove,
}: {
  icon: React.ReactNode;
  label: string;
  readOnly: boolean;
  onRemove: () => void;
}) {
  const { t } = useTranslation();
  return (
    <Badge
      variant="light"
      size="lg"
      tt="none"
      leftSection={icon}
      pr={readOnly ? undefined : 4}
      rightSection={
        readOnly ? undefined : (
          <CloseButton size="xs" aria-label={t('settings.role.remove', { name: label })} onClick={onRemove} />
        )
      }
    >
      {label}
    </Badge>
  );
}
