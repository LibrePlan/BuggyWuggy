// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Button, Group, Modal, Select, Stack, Text } from '@mantine/core';
import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useResolutions } from '../api/queries';
import type { TransitionResponse } from '../api/types';
import { StatusBadge } from './IssueIcons';

/** Asks for the resolution when a transition moves the issue to a done status. */
export function TransitionModal({
  transition,
  currentResolutionId,
  loading,
  onConfirm,
  onClose,
}: {
  transition: TransitionResponse | null;
  currentResolutionId: number | null;
  loading: boolean;
  onConfirm: (resolutionId: number | undefined) => void;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const resolutions = useResolutions().data ?? [];
  const [resolutionId, setResolutionId] = useState<string | null>(null);

  useEffect(() => {
    if (transition) {
      const preferred = currentResolutionId ?? resolutions.find((r) => r.isDefault)?.id;
      setResolutionId(preferred ? String(preferred) : null);
    }
  }, [transition, currentResolutionId, resolutions]);

  return (
    <Modal opened={transition !== null} onClose={onClose} title={transition?.name}>
      {transition && (
        <Stack>
          <Group gap={6}>
            <Text size="sm">{t('issue.transitionTo')}</Text>
            <StatusBadge status={transition.to} />
          </Group>
          <Select
            label={t('issue.resolution')}
            data={resolutions.map((r) => ({ value: String(r.id), label: r.name }))}
            value={resolutionId}
            onChange={setResolutionId}
            allowDeselect={false}
            data-autofocus
          />
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button loading={loading} onClick={() => onConfirm(resolutionId ? Number(resolutionId) : undefined)}>
              {transition.name}
            </Button>
          </Group>
        </Stack>
      )}
    </Modal>
  );
}
