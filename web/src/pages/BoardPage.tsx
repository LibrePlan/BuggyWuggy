// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import {
  closestCenter,
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  pointerWithin,
  useDroppable,
  useSensor,
  useSensors,
  type CollisionDetection,
  type DragEndEvent,
  type DragOverEvent,
  type DragStartEvent,
  type UniqueIdentifier,
} from '@dnd-kit/core';
import {
  arrayMove,
  SortableContext,
  sortableKeyboardCoordinates,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import {
  Alert,
  Anchor,
  Avatar,
  Badge,
  Breadcrumbs,
  Button,
  Group,
  Modal,
  Paper,
  SegmentedControl,
  Select,
  Stack,
  Text,
  Title,
  Tooltip,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconSettings } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { TFunction } from 'i18next';
import { useEffect, useMemo, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router';
import { get, post, seg } from '../api/client';
import { keys, useBoard, usePriorities, useResolutions } from '../api/queries';
import type {
  BoardColumn,
  BoardIssue,
  BoardResponse,
  MoveRequest,
  PriorityRef,
  PriorityResponse,
  Swimlanes,
  TransitionResponse,
  UserRef,
} from '../api/types';
import { SWIMLANES } from '../components/BoardConfigEditor';
import { IssueTypeIcon, PriorityIcon, StatusBadge } from '../components/IssueIcons';
import { ErrorAlert, isNotFound, PageLoader } from '../components/QueryState';
import { initials } from '../components/UserLabel';
import { formatUrgency } from '../lib/urgency';
import { notifyError } from '../lib/notify';
import { NotFoundPage } from './NotFoundPage';

/** A horizontal lane: the issues with one value of the lane field (null: without). */
interface Lane {
  id: string;
  value: string | null;
  label: string;
  user?: UserRef | null;
  epic?: BoardIssue['epic'];
  priority?: PriorityRef | null;
}

const ALL: Lane = { id: 'all', value: null, label: '' };

const cellId = (lane: string, column: number) => `cell|${lane}|${column}`;

const isCell = (id: UniqueIdentifier) => String(id).startsWith('cell|');

/**
 * Where a dragged card is: the cell under the pointer (with the keyboard: the nearest cell), and
 * in it the nearest card, or the cell itself when it is empty. (dnd-kit's closestCorners compares
 * the corners of the dragged card with those of every card and cell; the cells are as tall as the
 * board, so a card in the next column was nearly always "closer" than the cell under the pointer,
 * and a card dropped in another column went back to its own.)
 */
const boardCollision: CollisionDetection = (args) => {
  const cells = args.droppableContainers.filter((c) => isCell(c.id));
  const under = args.pointerCoordinates ? pointerWithin({ ...args, droppableContainers: cells }) : [];
  const cell = (under.length > 0 ? under : closestCenter({ ...args, droppableContainers: cells }))[0];
  if (!cell) return [];
  const cards = args.droppableContainers.filter((c) => !isCell(c.id) && c.data.current?.cell === cell.id);
  return cards.length > 0 ? closestCenter({ ...args, droppableContainers: cards }) : [cell];
};

function parseCell(id: string): { lane: string; column: number } {
  const [, lane, column] = id.split('|');
  return { lane: lane!, column: Number(column) };
}

function laneValue(issue: BoardIssue, by: Swimlanes): string | null {
  switch (by) {
    case 'ASSIGNEE':
      return issue.assignee ? String(issue.assignee.id) : null;
    case 'EPIC':
      return issue.epic?.key ?? null;
    case 'PRIORITY':
      return issue.priority ? String(issue.priority.id) : null;
    default:
      return null;
  }
}

const laneId = (by: Swimlanes, value: string | null) => (by === 'NONE' ? 'all' : `${by}:${value ?? '-'}`);

const keyNumber = (key: string) => Number(key.slice(key.lastIndexOf('-') + 1));

function storedLanes(projectKey: string): Swimlanes | null {
  try {
    const value = localStorage.getItem(`bw.board.lanes.${projectKey}`);
    return SWIMLANES.find((s) => s === value) ?? null;
  } catch {
    return null;
  }
}

function storeLanes(projectKey: string, value: Swimlanes) {
  try {
    localStorage.setItem(`bw.board.lanes.${projectKey}`, value);
  } catch {
    // Only a convenience.
  }
}

/** A move that needs a choice first: which transition, or which resolution. */
interface PendingMove {
  issue: BoardIssue;
  column: BoardColumn;
  candidates: TransitionResponse[];
  request: MoveRequest;
  lane: Lane;
  after: string | null;
  before: string | null;
}

/** The kanban board of a project: drag issues to another column, lane or place. */
export function BoardPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const projectKey = useParams().projectKey!;
  const board = useBoard(projectKey);
  const priorities = usePriorities().data;
  const queryClient = useQueryClient();
  const [chosenLanes, setChosenLanes] = useState<Swimlanes | null>(() => storedLanes(projectKey));
  const [activeKey, setActiveKey] = useState<string | null>(null);
  const [overCell, setOverCell] = useState<string | null>(null);
  const [pending, setPending] = useState<PendingMove | null>(null);
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  );

  const currentKey = board.data?.project.key;
  useEffect(() => {
    if (currentKey && currentKey !== projectKey) navigate(`/projects/${currentKey}/board`, { replace: true });
  }, [currentKey, projectKey, navigate]);

  const by: Swimlanes = chosenLanes ?? board.data?.swimlanes ?? 'NONE';
  const data = board.data;

  const lanes = useMemo(() => (data ? lanesOf(data.issues, by, priorities, t) : [ALL]), [data, by, priorities, t]);

  /** The keys of the issues per cell, in board order. */
  const cells = useMemo(() => {
    const map = new Map<string, string[]>();
    for (const issue of data?.issues ?? []) {
      const id = cellId(laneId(by, laneValue(issue, by)), issue.columnId);
      map.set(id, [...(map.get(id) ?? []), issue.key]);
    }
    return map;
  }, [data, by]);

  const move = useMutation({
    mutationFn: ({ request }: { request: MoveRequest; optimistic: (b: BoardResponse) => BoardResponse }) =>
      post<void>(`/api/v1/projects/${seg(projectKey)}/board/moves`, request),
    onMutate: async ({ optimistic }) => {
      await queryClient.cancelQueries({ queryKey: keys.board(projectKey), exact: true });
      queryClient.setQueryData<BoardResponse>(keys.board(projectKey), (old) => (old ? optimistic(old) : old));
    },
    onError: notifyError,
    onSettled: (_r, _e, { request }) => {
      void queryClient.invalidateQueries({ queryKey: keys.board(projectKey), exact: true });
      void queryClient.invalidateQueries({ queryKey: keys.issue(request.issue) });
    },
  });

  if (board.isPending) return <PageLoader />;
  if (board.isError) {
    return isNotFound(board.error) ? <NotFoundPage message={t('project.notFound')} /> : <ErrorAlert error={board.error} />;
  }
  const b = board.data;
  const byKey = new Map(b.issues.map((i) => [i.key, i]));
  const movable = b.permissions.transition || b.permissions.assign || b.permissions.edit;
  const activeIssue = activeKey ? byKey.get(activeKey) : undefined;
  const warn = (message: string) => notifications.show({ color: 'orange', message });

  function cellOf(id: string | number, data: Record<string, unknown> | undefined): string | null {
    if (typeof data?.cell === 'string') return data.cell;
    return isCell(id) ? String(id) : null;
  }

  function onDragStart(event: DragStartEvent) {
    setActiveKey(String(event.active.id));
  }

  function onDragOver(event: DragOverEvent) {
    setOverCell(event.over ? cellOf(event.over.id, event.over.data.current) : null);
  }

  async function onDragEnd(event: DragEndEvent) {
    setActiveKey(null);
    setOverCell(null);
    const { active, over } = event;
    if (!over || over.id === active.id) return;
    const key = String(active.id);
    const issue = byKey.get(key);
    const source = cellOf(active.id, active.data.current);
    const target = cellOf(over.id, over.data.current);
    if (!issue || !source || !target) return;

    // The new order of the target cell.
    const original = cells.get(target) ?? [];
    let list: string[];
    if (source === target) {
      list = over.id === target ? original : arrayMove(original, original.indexOf(key), original.indexOf(String(over.id)));
    } else {
      list = original.filter((k) => k !== key);
      if (over.id === target) {
        list.push(key);
      } else {
        const translated = active.rect.current.translated;
        const below = translated ? translated.top + translated.height / 2 > over.rect.top + over.rect.height / 2 : false;
        list.splice(list.indexOf(String(over.id)) + (below ? 1 : 0), 0, key);
      }
    }
    const position = list.indexOf(key);
    const after = list[position - 1] ?? null;
    const before = list[position + 1] ?? null;
    if (source === target && list.join() === original.join()) return;

    const { lane: targetLaneId, column: columnId } = parseCell(target);
    const column = b.columns.find((c) => c.id === columnId)!;
    const lane = lanes.find((l) => l.id === targetLaneId) ?? ALL;
    const columnChange = columnId !== issue.columnId;
    const laneChange = by !== 'NONE' && lane.value !== laneValue(issue, by);

    if (columnChange && !b.permissions.transition) return warn(t('board.cannot.transition'));
    if (laneChange) {
      if (by === 'ASSIGNEE' && !b.permissions.assign) return warn(t('board.cannot.assign'));
      if (by !== 'ASSIGNEE' && !b.permissions.edit) return warn(t('board.cannot.edit'));
      if (by === 'PRIORITY' && lane.value === null) return warn(t('board.cannot.noPriority'));
      if (by === 'EPIC' && issue.issueType.hierarchyLevel !== 0) {
        return warn(t('board.cannot.epic', { type: issue.issueType.name }));
      }
    }
    const rank = b.permissions.edit && (after !== null || before !== null);
    if (!columnChange && !laneChange && !rank) return warn(t('board.cannot.rank'));

    const request: MoveRequest = {
      issue: key,
      version: issue.version,
      lane: laneChange ? { by, value: lane.value } : undefined,
      after: rank ? (after ?? undefined) : undefined,
      before: rank ? (before ?? undefined) : undefined,
    };
    if (!columnChange) return send(request, issue, column, lane, after, before);

    let transitions: TransitionResponse[];
    try {
      transitions = await queryClient.fetchQuery({
        queryKey: keys.transitions(key),
        queryFn: () => get<TransitionResponse[]>(`/api/v1/issues/${seg(key)}/transitions`),
        staleTime: 0,
      });
    } catch (e) {
      return notifyError(e);
    }
    const candidates = transitions.filter((tr) => column.statuses.some((s) => s.id === tr.to.id));
    if (candidates.length === 0) {
      return warn(t('board.cannot.noTransition', { status: issue.status.name, column: column.name }));
    }
    if (candidates.length === 1 && !candidates[0]!.setsResolution) {
      return send({ ...request, transitionId: candidates[0]!.id }, issue, column, lane, after, before, candidates[0]);
    }
    setPending({ issue, column, candidates, request, lane, after, before });
  }

  function send(
    request: MoveRequest,
    issue: BoardIssue,
    column: BoardColumn,
    lane: Lane,
    after: string | null,
    before: string | null,
    transition?: TransitionResponse,
  ) {
    const optimistic = (old: BoardResponse): BoardResponse => {
      const changed: BoardIssue = {
        ...issue,
        columnId: column.id,
        status: transition?.to ?? issue.status,
        ...(request.lane && by === 'ASSIGNEE' ? { assignee: lane.user ?? undefined } : {}),
        ...(request.lane && by === 'EPIC' ? { epic: lane.epic ?? undefined } : {}),
        ...(request.lane && by === 'PRIORITY' ? { priority: lane.priority ?? undefined } : {}),
      };
      const index = old.issues.findIndex((i) => i.key === issue.key);
      const rest = old.issues.filter((i) => i.key !== issue.key);
      let at = after ? rest.findIndex((i) => i.key === after) + 1 : before ? rest.findIndex((i) => i.key === before) : index;
      if (at < 0) at = index;
      rest.splice(at, 0, changed);
      return { ...old, issues: rest };
    };
    move.mutate({ request, optimistic });
  }

  const columnCount = (column: BoardColumn) => b.issues.filter((i) => i.columnId === column.id).length;

  return (
    <Stack>
      <Breadcrumbs>
        <Anchor component={Link} to="/projects" size="sm">
          {t('projects.title')}
        </Anchor>
        <Anchor component={Link} to={`/projects/${b.project.key}`} size="sm">
          {b.project.name}
        </Anchor>
        <Text size="sm">{t('board.title')}</Text>
      </Breadcrumbs>
      <Group justify="space-between" align="flex-end">
        <Title order={2}>{t('board.titleFor', { name: b.project.name })}</Title>
        <Group gap="sm" align="flex-end">
          <Stack gap={2}>
            <Text size="xs" c="dimmed">
              {t('board.config.swimlanes')}
            </Text>
            <SegmentedControl
              size="xs"
              value={by}
              onChange={(v) => {
                setChosenLanes(v as Swimlanes);
                storeLanes(projectKey, v as Swimlanes);
              }}
              data={SWIMLANES.map((s) => ({ value: s, label: t(`board.swimlanes.${s}`) }))}
            />
          </Stack>
          {b.permissions.configure && (
            <Button
              variant="default"
              leftSection={<IconSettings size={16} />}
              component={Link}
              to={`/projects/${b.project.key}/settings?tab=board`}
            >
              {t('board.configure')}
            </Button>
          )}
        </Group>
      </Group>
      {!movable && (
        <Text size="sm" c="dimmed">
          {t('board.readOnly')}
        </Text>
      )}
      {b.unmappedStatuses.length > 0 && (
        <Alert color="yellow" variant="light">
          {t('board.unmapped', { statuses: b.unmappedStatuses.map((s) => s.name).join(', ') })}
        </Alert>
      )}
      {b.truncated && (
        <Alert color="yellow" variant="light">
          {t('board.truncated', { count: b.issues.length })}
        </Alert>
      )}

      <DndContext
        sensors={sensors}
        collisionDetection={boardCollision}
        onDragStart={onDragStart}
        onDragOver={onDragOver}
        onDragEnd={(e) => void onDragEnd(e)}
        onDragCancel={() => {
          setActiveKey(null);
          setOverCell(null);
        }}
      >
        <div className="bw-board" style={{ gridTemplateColumns: `repeat(${b.columns.length}, minmax(240px, 1fr))` }}>
          {b.columns.map((column) => (
            <Group key={column.id} justify="center" gap={6} wrap="nowrap" px={6}>
              <Tooltip label={column.statuses.map((s) => s.name).join(', ') || t('board.config.noStatuses')}>
                <Text fw={600} size="sm" tt="uppercase" c="dimmed" truncate>
                  {column.name}
                </Text>
              </Tooltip>
              <Text size="sm" c="dimmed" style={{ flexShrink: 0 }}>
                ({columnCount(column)})
              </Text>
            </Group>
          ))}
          {lanes.map((lane) => (
            <LaneRows
              key={lane.id}
              lane={lane}
              showHeader={by !== 'NONE'}
              columns={b.columns}
              cells={cells}
              byKey={byKey}
              overCell={overCell}
              disabled={!movable}
              by={by}
            />
          ))}
        </div>
        <DragOverlay>
          {activeIssue ? <CardContent issue={activeIssue} column={b.columns.find((c) => c.id === activeIssue.columnId)} by={by} /> : null}
        </DragOverlay>
      </DndContext>

      <MoveModal
        pending={pending}
        loading={move.isPending}
        onClose={() => setPending(null)}
        onConfirm={(transition, resolutionId) => {
          const p = pending!;
          setPending(null);
          send({ ...p.request, transitionId: transition.id, resolutionId }, p.issue, p.column, p.lane, p.after,
            p.before, transition);
        }}
      />
    </Stack>
  );
}

function lanesOf(
  issues: BoardIssue[],
  by: Swimlanes,
  priorities: PriorityResponse[] | undefined,
  t: TFunction,
): Lane[] {
  switch (by) {
    case 'ASSIGNEE': {
      const users = new Map<number, UserRef>();
      issues.forEach((i) => i.assignee && users.set(i.assignee.id, i.assignee));
      return [
        ...[...users.values()]
          .sort((a, c) => a.displayName.localeCompare(c.displayName))
          .map((u) => ({ id: laneId(by, String(u.id)), value: String(u.id), label: u.displayName, user: u })),
        { id: laneId(by, null), value: null, label: t('board.lane.unassigned'), user: null },
      ];
    }
    case 'EPIC': {
      const epics = new Map<string, NonNullable<BoardIssue['epic']>>();
      issues.forEach((i) => i.epic && epics.set(i.epic.key, i.epic));
      return [
        ...[...epics.values()]
          .sort((a, c) => keyNumber(a.key) - keyNumber(c.key))
          .map((e) => ({ id: laneId(by, e.key), value: e.key, label: `${e.key} ${e.summary}`, epic: e })),
        { id: laneId(by, null), value: null, label: t('board.lane.noEpic'), epic: null },
      ];
    }
    case 'PRIORITY': {
      const lanes: Lane[] = (priorities ?? []).map((p) => ({
        id: laneId(by, String(p.id)),
        value: String(p.id),
        label: p.name,
        priority: { id: p.id, name: p.name, icon: p.icon ?? null, color: p.color ?? null },
      }));
      issues
        .filter((i) => i.priority && !lanes.some((l) => l.value === String(i.priority!.id)))
        .forEach((i) => lanes.push({ id: laneId(by, String(i.priority!.id)), value: String(i.priority!.id), label: i.priority!.name, priority: i.priority }));
      if (issues.some((i) => !i.priority)) lanes.push({ id: laneId(by, null), value: null, label: t('board.lane.noPriority'), priority: null });
      return lanes;
    }
    default:
      return [ALL];
  }
}

function LaneRows({
  lane,
  showHeader,
  columns,
  cells,
  byKey,
  overCell,
  disabled,
  by,
}: {
  lane: Lane;
  showHeader: boolean;
  columns: BoardColumn[];
  cells: Map<string, string[]>;
  byKey: Map<string, BoardIssue>;
  overCell: string | null;
  disabled: boolean;
  by: Swimlanes;
}) {
  const { t } = useTranslation();
  const count = columns.reduce((n, c) => n + (cells.get(cellId(lane.id, c.id))?.length ?? 0), 0);
  return (
    <>
      {showHeader && (
        <Group className="bw-board-lane" gap="xs">
          {lane.priority && <PriorityIcon priority={lane.priority} size={16} />}
          {lane.user && (
            <Avatar size="sm" radius="xl" color="initials" name={lane.user.displayName}>
              {initials(lane.user.displayName)}
            </Avatar>
          )}
          {lane.epic ? (
            <Anchor component={Link} to={`/browse/${lane.epic.key}`} fw={600} size="sm">
              {lane.label}
            </Anchor>
          ) : (
            <Text fw={600} size="sm">
              {lane.label}
            </Text>
          )}
          <Text size="sm" c="dimmed">
            {t('board.issueCount', { count })}
          </Text>
        </Group>
      )}
      {columns.map((column) => {
        const id = cellId(lane.id, column.id);
        const items = cells.get(id) ?? [];
        return (
          <BoardCell key={id} id={id} items={items} over={overCell === id}>
            {items.map((k) => (
              <BoardCard key={k} issue={byKey.get(k)!} cell={id} column={column} disabled={disabled} by={by} />
            ))}
          </BoardCell>
        );
      })}
    </>
  );
}

function BoardCell({ id, items, over, children }: { id: string; items: string[]; over: boolean; children: ReactNode }) {
  const { setNodeRef } = useDroppable({ id, data: { cell: id } });
  return (
    <div ref={setNodeRef} className="bw-board-cell" data-over={over || undefined}>
      <SortableContext id={id} items={items} strategy={verticalListSortingStrategy}>
        <Stack gap={6}>{children}</Stack>
      </SortableContext>
    </div>
  );
}

function BoardCard({
  issue,
  cell,
  column,
  disabled,
  by,
}: {
  issue: BoardIssue;
  cell: string;
  column: BoardColumn;
  disabled: boolean;
  by: Swimlanes;
}) {
  const navigate = useNavigate();
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: issue.key,
    data: { cell },
    disabled,
  });
  return (
    <div
      ref={setNodeRef}
      style={{ transform: CSS.Translate.toString(transform), transition }}
      className="bw-board-card"
      data-dragging={isDragging || undefined}
      data-fixed={disabled || undefined}
      {...attributes}
      {...listeners}
      onClick={() => navigate(`/browse/${issue.key}`)}
      onKeyDown={(e) => {
        listeners?.onKeyDown?.(e);
        if (disabled && e.key === 'Enter') navigate(`/browse/${issue.key}`);
      }}
    >
      <CardContent issue={issue} column={column} by={by} />
    </div>
  );
}

function CardContent({ issue, column, by }: { issue: BoardIssue; column: BoardColumn | undefined; by: Swimlanes }) {
  const { t } = useTranslation();
  return (
    <Paper withBorder shadow="xs" p="xs" radius="sm">
      <Stack gap={6}>
        <Group gap={6} wrap="nowrap" justify="space-between">
          <Group gap={6} wrap="nowrap">
            <IssueTypeIcon type={issue.issueType} size={16} />
            <Anchor
              component={Link}
              to={`/browse/${issue.key}`}
              size="xs"
              fw={500}
              c={issue.status.category === 'DONE' ? 'dimmed' : undefined}
              td={issue.status.category === 'DONE' ? 'line-through' : undefined}
              onClick={(e) => e.stopPropagation()}
            >
              {issue.key}
            </Anchor>
          </Group>
          {issue.urgency != null && (
            <Tooltip label={t('taskwarrior.urgency')}>
              <Text size="xs" c="dimmed">
                {formatUrgency(issue.urgency)}
              </Text>
            </Tooltip>
          )}
        </Group>
        <Text size="sm" lineClamp={3}>
          {issue.summary}
        </Text>
        <Group gap={6} wrap="nowrap" justify="space-between">
          <Group gap={6} wrap="nowrap" style={{ minWidth: 0 }}>
            {issue.priority && (
              <Tooltip label={issue.priority.name}>
                <span>
                  <PriorityIcon priority={issue.priority} size={16} />
                </span>
              </Tooltip>
            )}
            {column && column.statuses.length > 1 && <StatusBadge status={issue.status} size="xs" />}
            {by !== 'EPIC' && issue.epic && issue.epic.key !== issue.key && (
              <Badge size="xs" variant="light" color="violet" maw={140} title={issue.epic.summary}>
                {issue.epic.summary}
              </Badge>
            )}
          </Group>
          {issue.assignee && (
            <Tooltip label={issue.assignee.displayName}>
              <Avatar size="sm" radius="xl" color="initials" name={issue.assignee.displayName}>
                {initials(issue.assignee.displayName)}
              </Avatar>
            </Tooltip>
          )}
        </Group>
      </Stack>
    </Paper>
  );
}

/** Asks which transition to use (when several lead to the column) and the resolution (for done). */
function MoveModal({
  pending,
  loading,
  onClose,
  onConfirm,
}: {
  pending: PendingMove | null;
  loading: boolean;
  onClose: () => void;
  onConfirm: (transition: TransitionResponse, resolutionId: number | undefined) => void;
}) {
  const { t } = useTranslation();
  const resolutions = useResolutions().data ?? [];
  const [transitionId, setTransitionId] = useState<string | null>(null);
  const [resolutionId, setResolutionId] = useState<string | null>(null);

  useEffect(() => {
    if (pending) {
      setTransitionId(String(pending.candidates[0]!.id));
      const preferred = resolutions.find((r) => r.isDefault)?.id;
      setResolutionId(preferred ? String(preferred) : null);
    }
  }, [pending, resolutions]);

  const transition = pending?.candidates.find((c) => String(c.id) === transitionId);
  return (
    <Modal opened={pending !== null} onClose={onClose} title={pending ? t('board.moveTitle', { key: pending.issue.key, column: pending.column.name }) : ''}>
      {pending && (
        <Stack>
          {pending.candidates.length > 1 ? (
            <Select
              label={t('board.transition')}
              data={pending.candidates.map((c) => ({ value: String(c.id), label: `${c.name} → ${c.to.name}` }))}
              value={transitionId}
              onChange={setTransitionId}
              allowDeselect={false}
              data-autofocus
            />
          ) : (
            <Group gap={6}>
              <Text size="sm">{t('issue.transitionTo')}</Text>
              <StatusBadge status={pending.candidates[0]!.to} />
            </Group>
          )}
          {transition?.setsResolution && (
            <Select
              label={t('issue.resolution')}
              data={resolutions.map((r) => ({ value: String(r.id), label: r.name }))}
              value={resolutionId}
              onChange={setResolutionId}
              allowDeselect={false}
            />
          )}
          <Group justify="flex-end">
            <Button variant="default" onClick={onClose}>
              {t('common.cancel')}
            </Button>
            <Button
              loading={loading}
              disabled={!transition}
              onClick={() => onConfirm(transition!, transition!.setsResolution && resolutionId ? Number(resolutionId) : undefined)}
            >
              {transition?.name ?? t('board.move')}
            </Button>
          </Group>
        </Stack>
      )}
    </Modal>
  );
}
