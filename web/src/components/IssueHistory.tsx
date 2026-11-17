// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Anchor, Badge, Box, Group, Stack, Text, VisuallyHidden } from '@mantine/core';
import { IconArrowRight } from '@tabler/icons-react';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { useHistory } from '../api/queries';
import { formatDate, formatDateTime } from '../lib/format';
import { historyRows, type HistoryRow, type HistoryValue } from '../lib/history';
import { ErrorAlert, PageLoader } from './QueryState';
import { UserLabel } from './UserLabel';

const CLAMP_LINES = 4;

/** Long text clamped to a few lines, with "Show more" when it does not fit. */
function Clamped({ children, dimmed }: { children: ReactNode; dimmed?: boolean }) {
  const { t } = useTranslation();
  const ref = useRef<HTMLParagraphElement>(null);
  const [open, setOpen] = useState(false);
  const [overflows, setOverflows] = useState(false);
  useEffect(() => {
    const el = ref.current;
    if (!el || open) return;
    const measure = () => setOverflows(el.scrollHeight > el.clientHeight + 1);
    measure();
    const observer = new ResizeObserver(measure);
    observer.observe(el);
    return () => observer.disconnect();
  }, [open, children]);
  return (
    <div>
      <Text ref={ref} size="sm" c={dimmed ? 'dimmed' : undefined} className="bw-pre-wrap" lineClamp={open ? undefined : CLAMP_LINES}>
        {children}
      </Text>
      {(overflows || open) && (
        <Anchor component="button" type="button" size="xs" onClick={() => setOpen((o) => !o)}>
          {open ? t('history.showLess') : t('history.showMore')}
        </Anchor>
      )}
    </div>
  );
}

function Value({ value, old }: { value: HistoryValue | null; old?: boolean }) {
  const { i18n } = useTranslation();
  if (!value) {
    return (
      <Text size="sm" c="dimmed">
        —
      </Text>
    );
  }
  if (value.date) {
    return (
      <Text size="sm" c={old ? 'dimmed' : undefined}>
        {formatDate(value.date, i18n.language)}
      </Text>
    );
  }
  if (value.issueKey) {
    return (
      <Anchor component={Link} to={`/browse/${value.issueKey}`} size="sm" className="bw-pre-wrap">
        {value.text}
      </Anchor>
    );
  }
  if (value.url) {
    return (
      <Anchor href={value.url} target="_blank" rel="noopener noreferrer" size="sm" className="bw-pre-wrap">
        {value.text}
      </Anchor>
    );
  }
  return <Clamped dimmed={old}>{value.text}</Clamped>;
}

function Row({ row }: { row: HistoryRow }) {
  const { t } = useTranslation();
  const field = (
    <Text size="sm" fw={500} className="bw-history-field">
      {t(`history.field.${row.field}`, { defaultValue: row.field })}
    </Text>
  );
  if (row.kind === 'set') {
    return (
      <div className="bw-history-row">
        {field}
        <Group gap={6} className="bw-history-values">
          {row.added.map((v, i) => (
            <Badge key={`a${i}`} color="green" variant="light" tt="none" title={t('history.added')}>
              <VisuallyHidden>{t('history.added')}: </VisuallyHidden>+ {v}
            </Badge>
          ))}
          {row.removed.map((v, i) => (
            <Badge key={`r${i}`} color="red" variant="light" tt="none" title={t('history.removed')}>
              <VisuallyHidden>{t('history.removed')}: </VisuallyHidden>− {v}
            </Badge>
          ))}
        </Group>
      </div>
    );
  }
  return (
    <div className="bw-history-row">
      {field}
      <Box miw={0}>
        <Value value={row.from} old />
      </Box>
      <Box c="dimmed" pt={2}>
        <IconArrowRight size={14} aria-hidden />
        <VisuallyHidden>{t('history.to')}</VisuallyHidden>
      </Box>
      <Box miw={0}>
        <Value value={row.to} />
      </Box>
    </div>
  );
}

/** The change history of an issue, newest first: per change, each field from its old to its new value. */
export function IssueHistory({ issueKey }: { issueKey: string }) {
  const { t, i18n } = useTranslation();
  const history = useHistory(issueKey);

  if (history.isPending) return <PageLoader />;
  if (history.isError) return <ErrorAlert error={history.error} />;
  if (history.data.length === 0) return <Text c="dimmed" size="sm">{t('history.empty')}</Text>;

  const entries = [...history.data].reverse();
  return (
    <Stack gap="lg">
      {entries.map((entry) => (
        <div key={entry.id}>
          <Group gap={6} mb={4}>
            <UserLabel user={entry.author} emptyLabel={t('history.system')} />
            <Text size="sm" c="dimmed">
              {t('history.madeChanges')} · {formatDateTime(entry.createdAt, i18n.language)}
            </Text>
          </Group>
          {historyRows(entry.items).map((row, index) => (
            <Row key={index} row={row} />
          ))}
        </div>
      ))}
    </Stack>
  );
}
