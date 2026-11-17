// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { ActionIcon, Anchor, Group, Input, Menu, Paper, SegmentedControl, Text, Textarea, Tooltip } from '@mantine/core';
import { useElementSize } from '@mantine/hooks';
import {
  IconBlockquote,
  IconBold,
  IconCode,
  IconItalic,
  IconLink,
  IconList,
  IconListCheck,
  IconListNumbers,
  IconMarkdown,
  IconPhoto,
  IconSourceCode,
  IconStrikethrough,
} from '@tabler/icons-react';
import { useDeferredValue, useEffect, useRef, useState, type FocusEvent, type KeyboardEvent, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { MarkdownAttachment } from '../lib/markdown';
import { Markdown } from './Markdown';

/** Below this width the preview is a tab instead of a column next to the text. */
const SIDE_BY_SIDE = 640;

export interface MarkdownEditorProps {
  /** Optional only for the spread of Mantine's form.getInputProps(), which always sets it. */
  value?: string;
  onChange: (value: string) => void;
  label?: ReactNode;
  description?: ReactNode;
  error?: ReactNode;
  required?: boolean;
  placeholder?: string;
  minRows?: number;
  maxRows?: number;
  maxLength?: number;
  autoFocus?: boolean;
  'aria-label'?: string;
  onKeyDown?: (e: KeyboardEvent<HTMLTextAreaElement>) => void;
  onFocus?: (e: FocusEvent<HTMLTextAreaElement>) => void;
  onBlur?: (e: FocusEvent<HTMLTextAreaElement>) => void;
  /** The issue's attachments: images can be inserted, and are shown in the preview. */
  attachments?: MarkdownAttachment[];
  /** The text size of the preview: as where the text is shown later. */
  previewSize?: 'sm' | 'md';
}

/**
 * A text field for Markdown with a toolbar and a live preview: to the right of the text when
 * there is room, otherwise behind a "Preview" tab. Ctrl+B, Ctrl+I and Ctrl+K make text bold,
 * italic or a link. Toolbar changes go through the browser's editing, so Ctrl+Z undoes them.
 */
export function MarkdownEditor({
  value = '',
  onChange,
  label,
  description,
  error,
  required,
  placeholder,
  minRows = 4,
  maxRows = 20,
  maxLength,
  autoFocus,
  'aria-label': ariaLabel,
  onKeyDown,
  onFocus,
  onBlur,
  attachments,
  previewSize = 'md',
}: MarkdownEditorProps) {
  const { t } = useTranslation();
  const { ref: box, width } = useElementSize();
  const sideBySide = width >= SIDE_BY_SIDE;
  const [tab, setTab] = useState<'write' | 'preview'>('write');
  const writing = sideBySide || tab === 'write';
  const area = useRef<HTMLTextAreaElement>(null);
  const preview = useRef<HTMLDivElement>(null);
  const [areaHeight, setAreaHeight] = useState<number>();
  const shown = useDeferredValue(value);

  // The preview is as high as the text field, and scrolls along with it.
  useEffect(() => {
    const el = area.current;
    if (!el) return;
    const observer = new ResizeObserver(() => setAreaHeight(el.offsetHeight));
    observer.observe(el);
    return () => observer.disconnect();
    // A new textarea element when switching between side by side and tabs.
  }, [writing, sideBySide]);
  const syncScroll = () => {
    const a = area.current;
    const p = preview.current;
    if (!a || !p || !sideBySide) return;
    const ratio = a.scrollTop / Math.max(1, a.scrollHeight - a.clientHeight);
    p.scrollTop = ratio * (p.scrollHeight - p.clientHeight);
  };

  /** Replaces start..end by text and selects selStart..selEnd (relative to start). */
  const replace = (start: number, end: number, text: string, selStart: number, selEnd: number) => {
    const el = area.current;
    if (!el) return;
    el.focus();
    el.setSelectionRange(start, end);
    // insertText keeps the browser's undo history; its input event updates the value.
    if (document.execCommand('insertText', false, text)) {
      el.setSelectionRange(start + selStart, start + selEnd);
    } else {
      // Without it: set the value; React puts the cursor at the end, so select after that.
      onChange(el.value.slice(0, start) + text + el.value.slice(end));
      requestAnimationFrame(() => el.setSelectionRange(start + selStart, start + selEnd));
    }
  };
  const selection = () => {
    const el = area.current!;
    return { start: el.selectionStart, end: el.selectionEnd, text: el.value.slice(el.selectionStart, el.selectionEnd) };
  };
  const wrap = (marker: string) => {
    const s = selection();
    const inner = s.text || t('markdown.sample');
    replace(s.start, s.end, marker + inner + marker, marker.length, marker.length + inner.length);
  };
  const link = () => {
    const s = selection();
    const text = s.text || t('markdown.linkText');
    const url = 'https://';
    replace(s.start, s.end, `[${text}](${url})`, text.length + 3, text.length + 3 + url.length);
  };
  const lines = (prefix: (index: number) => string) => {
    const el = area.current!;
    const v = el.value;
    const start = v.lastIndexOf('\n', el.selectionStart - 1) + 1;
    let end = v.indexOf('\n', el.selectionEnd > el.selectionStart ? el.selectionEnd - 1 : el.selectionEnd);
    if (end < 0) end = v.length;
    const text = v
      .slice(start, end)
      .split('\n')
      .map((line, i) => prefix(i) + line)
      .join('\n');
    replace(start, end, text, text.length, text.length);
  };
  const codeBlock = () => {
    const s = selection();
    const before = s.start > 0 && area.current!.value[s.start - 1] !== '\n' ? '\n' : '';
    const inner = s.text || t('markdown.sampleCode');
    const open = `${before}\`\`\`\n`;
    replace(s.start, s.end, `${open}${inner}\n\`\`\`\n`, open.length, open.length + inner.length);
  };
  const image = (a: MarkdownAttachment) => {
    const s = selection();
    const target = /[\s()<>]/.test(a.fileName) ? `<attachment:${a.fileName}>` : `attachment:${a.fileName}`;
    const alt = s.text || a.fileName.replace(/\.[^.]+$/, '');
    const text = `![${alt}](${target})`;
    replace(s.start, s.end, text, text.length, text.length);
  };

  const keyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
    if ((e.ctrlKey || e.metaKey) && !e.altKey && !e.shiftKey) {
      const action = { b: () => wrap('**'), i: () => wrap('_'), k: link }[e.key.toLowerCase()];
      if (action) {
        e.preventDefault();
        action();
        return;
      }
    }
    onKeyDown?.(e);
  };

  const images = attachments?.filter((a) => a.mediaType.startsWith('image/')) ?? [];
  const tool = (labelKey: string, icon: ReactNode, action: () => void) => (
    <Tooltip label={t(labelKey)} openDelay={400} key={labelKey}>
      <ActionIcon variant="subtle" color="gray" size="sm" aria-label={t(labelKey)} disabled={!writing} onClick={action}>
        {icon}
      </ActionIcon>
    </Tooltip>
  );

  const textarea = (
    <Textarea
      ref={area}
      value={value}
      onChange={(e) => onChange(e.currentTarget.value)}
      onKeyDown={keyDown}
      onFocus={onFocus}
      onBlur={onBlur}
      onScroll={syncScroll}
      placeholder={placeholder}
      aria-label={ariaLabel ?? (typeof label === 'string' ? label : undefined)}
      autosize
      minRows={minRows}
      maxRows={maxRows}
      maxLength={maxLength}
      autoFocus={autoFocus}
      data-autofocus={autoFocus || undefined}
      error={!!error}
    />
  );
  const previewPane = (
    <Paper
      ref={preview}
      withBorder
      px="sm"
      py={6}
      className="bw-md-preview"
      aria-label={t('markdown.preview')}
      style={sideBySide ? { height: areaHeight } : { minHeight: areaHeight }}
    >
      {shown.trim() ? (
        <Markdown text={shown} attachments={attachments} size={previewSize} />
      ) : (
        <Text size="sm" c="dimmed">
          {t('markdown.nothingToPreview')}
        </Text>
      )}
    </Paper>
  );

  return (
    <Input.Wrapper label={label} description={description} error={error} required={required}>
      <div ref={box}>
        <Group justify="space-between" gap="xs" wrap="nowrap" my={4}>
          <Group gap={2} wrap="wrap">
            {tool('markdown.bold', <IconBold size={16} />, () => wrap('**'))}
            {tool('markdown.italic', <IconItalic size={16} />, () => wrap('_'))}
            {tool('markdown.strikethrough', <IconStrikethrough size={16} />, () => wrap('~~'))}
            {tool('markdown.code', <IconCode size={16} />, () => wrap('`'))}
            {tool('markdown.link', <IconLink size={16} />, link)}
            {tool('markdown.bulletList', <IconList size={16} />, () => lines(() => '- '))}
            {tool('markdown.numberedList', <IconListNumbers size={16} />, () => lines((i) => `${i + 1}. `))}
            {tool('markdown.taskList', <IconListCheck size={16} />, () => lines(() => '- [ ] '))}
            {tool('markdown.quote', <IconBlockquote size={16} />, () => lines(() => '> '))}
            {tool('markdown.codeBlock', <IconSourceCode size={16} />, codeBlock)}
            {images.length > 0 && (
              <Menu position="bottom-start" withinPortal>
                <Menu.Target>
                  <Tooltip label={t('markdown.image')} openDelay={400}>
                    <ActionIcon variant="subtle" color="gray" size="sm" aria-label={t('markdown.image')} disabled={!writing}>
                      <IconPhoto size={16} />
                    </ActionIcon>
                  </Tooltip>
                </Menu.Target>
                <Menu.Dropdown>
                  {images.map((a) => (
                    <Menu.Item key={a.id} onClick={() => image(a)}>
                      {a.fileName}
                    </Menu.Item>
                  ))}
                </Menu.Dropdown>
              </Menu>
            )}
          </Group>
          <Group gap="xs" wrap="nowrap">
            {!sideBySide && (
              <SegmentedControl
                size="xs"
                value={tab}
                onChange={(v) => setTab(v as 'write' | 'preview')}
                data={[
                  { value: 'write', label: t('markdown.write') },
                  { value: 'preview', label: t('markdown.preview') },
                ]}
              />
            )}
            <Tooltip label={t('markdown.help')} openDelay={400}>
              <Anchor
                href="/help/user-guide#formatting-text-with-markdown"
                target="_blank"
                c="dimmed"
                aria-label={t('markdown.help')}
                display="flex"
              >
                <IconMarkdown size={20} />
              </Anchor>
            </Tooltip>
          </Group>
        </Group>
        {sideBySide ? (
          <div className="bw-md-split">
            {textarea}
            {previewPane}
          </div>
        ) : tab === 'write' ? (
          textarea
        ) : (
          previewPane
        )}
      </div>
    </Input.Wrapper>
  );
}
