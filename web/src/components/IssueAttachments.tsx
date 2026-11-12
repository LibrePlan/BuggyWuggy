// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import {
  ActionIcon,
  Anchor,
  Box,
  Button,
  Group,
  Image,
  Modal,
  Paper,
  SimpleGrid,
  Stack,
  Table,
  Text,
  Title,
  Tooltip,
  UnstyledButton,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconDownload, IconFile, IconPaperclip, IconTrash } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRef, useState, type DragEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { del, seg, upload } from '../api/client';
import { keys, useAttachments, useAttachmentSettings } from '../api/queries';
import type { AttachmentResponse } from '../api/types';
import { formatBytes, formatDateTime } from '../lib/format';
import { UserLabel } from './UserLabel';

const contentUrl = (a: AttachmentResponse) => `/api/v1/attachments/${a.id}/content`;

/**
 * The "Attachments" section: images as thumbnails, other files as a list. Files can be added
 * with the button or by dropping them on the section. Hidden when there are no attachments and
 * the user cannot add any.
 */
export function IssueAttachments({ issueKey, canUpload }: { issueKey: string; canUpload: boolean }) {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const attachments = useAttachments(issueKey);
  const settings = useAttachmentSettings().data;
  const input = useRef<HTMLInputElement>(null);
  const [dragging, setDragging] = useState(false);
  const [preview, setPreview] = useState<AttachmentResponse | null>(null);

  const invalidate = () => {
    void queryClient.invalidateQueries({ queryKey: keys.attachments(issueKey) });
    void queryClient.invalidateQueries({ queryKey: keys.history(issueKey) });
    void queryClient.invalidateQueries({ queryKey: keys.issue(issueKey), exact: true });
  };
  const showError = (e: Error) => notifications.show({ color: 'red', title: t('error.title'), message: e.message });

  const add = useMutation({
    mutationFn: (files: File[]) => upload<AttachmentResponse[]>(`/api/v1/issues/${seg(issueKey)}/attachments`, files),
    onSuccess: invalidate,
    onError: showError,
  });
  const remove = useMutation({
    mutationFn: (a: AttachmentResponse) => del(`/api/v1/issues/${seg(issueKey)}/attachments/${a.id}`),
    onSuccess: () => {
      setPreview(null);
      invalidate();
    },
    onError: showError,
  });

  function submit(files: File[]) {
    if (files.length === 0 || add.isPending) return;
    // Checked here too, so a big file is refused before it is sent rather than after.
    if (settings) {
      const tooLarge = files.find((f) => f.size > settings.maxFileSize);
      const total = files.reduce((sum, f) => sum + f.size, 0);
      if (tooLarge || total > settings.maxRequestSize) {
        showError(
          new Error(
            tooLarge
              ? t('attachment.tooLarge', { name: tooLarge.name, max: formatBytes(settings.maxFileSize, i18n.language) })
              : t('attachment.tooLargeTotal', { max: formatBytes(settings.maxRequestSize, i18n.language) }),
          ),
        );
        return;
      }
    }
    add.mutate(files);
  }

  const dropProps = canUpload
    ? {
        onDragOver: (e: DragEvent) => {
          if (!e.dataTransfer.types.includes('Files')) return;
          e.preventDefault();
          setDragging(true);
        },
        onDragLeave: (e: DragEvent) => {
          if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setDragging(false);
        },
        onDrop: (e: DragEvent) => {
          e.preventDefault();
          setDragging(false);
          submit(Array.from(e.dataTransfer.files));
        },
      }
    : {};

  const items = attachments.data ?? [];
  if (items.length === 0 && !canUpload) return null;
  const images = items.filter((a) => a.previewable);
  const files = items.filter((a) => !a.previewable);

  return (
    <Stack gap="xs" {...dropProps}>
      <Group justify="space-between">
        <Title order={4}>{t('attachment.title')}</Title>
        {canUpload && (
          <>
            <Button
              size="xs"
              variant="subtle"
              leftSection={<IconPaperclip size={14} />}
              loading={add.isPending}
              onClick={() => input.current?.click()}
            >
              {t('attachment.add')}
            </Button>
            <input
              ref={input}
              type="file"
              multiple
              hidden
              onChange={(e) => {
                submit(Array.from(e.currentTarget.files ?? []));
                e.currentTarget.value = '';
              }}
            />
          </>
        )}
      </Group>

      {canUpload && (dragging || items.length === 0) && (
        <Paper
          withBorder
          p="md"
          style={{
            borderStyle: 'dashed',
            borderColor: dragging ? 'var(--mantine-primary-color-filled)' : undefined,
            textAlign: 'center',
          }}
        >
          <Text c="dimmed" size="sm">
            {t('attachment.drop')}
            {settings && ` ${t('attachment.maxSize', { max: formatBytes(settings.maxFileSize, i18n.language) })}`}
          </Text>
        </Paper>
      )}

      {images.length > 0 && (
        <SimpleGrid cols={{ base: 2, sm: 4 }} spacing="xs">
          {images.map((a) => (
            <Tooltip key={a.id} label={a.fileName} openDelay={400}>
              <UnstyledButton onClick={() => setPreview(a)} aria-label={t('attachment.preview', { name: a.fileName })}>
                <Paper withBorder radius="sm" style={{ overflow: 'hidden' }}>
                  <Image src={contentUrl(a)} alt={a.fileName} h={110} fit="cover" loading="lazy" />
                  <Text size="xs" px={6} py={4} truncate>
                    {a.fileName}
                  </Text>
                </Paper>
              </UnstyledButton>
            </Tooltip>
          ))}
        </SimpleGrid>
      )}

      {files.length > 0 && (
        <Table verticalSpacing={6}>
          <Table.Tbody>
            {files.map((a) => (
              <Table.Tr key={a.id}>
                <Table.Td w={30}>
                  <IconFile size={16} />
                </Table.Td>
                <Table.Td>
                  <Anchor href={contentUrl(a)} size="sm" download={a.fileName}>
                    {a.fileName}
                  </Anchor>
                </Table.Td>
                <Table.Td w={90}>
                  <Text size="sm" c="dimmed">
                    {formatBytes(a.size, i18n.language)}
                  </Text>
                </Table.Td>
                <Table.Td w={160} visibleFrom="sm">
                  <UserLabel user={a.author} />
                </Table.Td>
                <Table.Td w={150} visibleFrom="sm">
                  <Text size="sm" c="dimmed">
                    {formatDateTime(a.createdAt, i18n.language)}
                  </Text>
                </Table.Td>
                <Table.Td w={36}>
                  {a.canDelete && <DeleteButton attachment={a} pending={remove.isPending} onDelete={remove.mutate} />}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}

      <Modal opened={preview !== null} onClose={() => setPreview(null)} title={preview?.fileName} size="xl">
        {preview && (
          <Stack>
            <Box style={{ textAlign: 'center' }}>
              <Image src={contentUrl(preview)} alt={preview.fileName} fit="contain" mah="70vh" />
            </Box>
            <Group justify="space-between">
              <Group gap="xs">
                <UserLabel user={preview.author} />
                <Text size="sm" c="dimmed">
                  {formatDateTime(preview.createdAt, i18n.language)} · {formatBytes(preview.size, i18n.language)}
                </Text>
              </Group>
              <Group gap="xs">
                <Button
                  component="a"
                  href={contentUrl(preview)}
                  download={preview.fileName}
                  size="xs"
                  variant="default"
                  leftSection={<IconDownload size={14} />}
                >
                  {t('attachment.download')}
                </Button>
                {preview.canDelete && (
                  <DeleteButton attachment={preview} pending={remove.isPending} onDelete={remove.mutate} />
                )}
              </Group>
            </Group>
          </Stack>
        )}
      </Modal>
    </Stack>
  );
}

function DeleteButton({
  attachment,
  pending,
  onDelete,
}: {
  attachment: AttachmentResponse;
  pending: boolean;
  onDelete: (a: AttachmentResponse) => void;
}) {
  const { t } = useTranslation();
  const label = t('attachment.delete', { name: attachment.fileName });
  return (
    <ActionIcon
      variant="subtle"
      color="gray"
      size="sm"
      aria-label={label}
      title={label}
      loading={pending}
      onClick={() => {
        if (window.confirm(t('attachment.deleteConfirm', { name: attachment.fileName }))) onDelete(attachment);
      }}
    >
      <IconTrash size={14} />
    </ActionIcon>
  );
}
