// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Typography } from '@mantine/core';
import { useMemo, type MouseEvent } from 'react';
import { useNavigate } from 'react-router';
import { useProjectKeys } from '../api/queries';
import { renderMarkdown, type MarkdownAttachment } from '../lib/markdown';

/**
 * A description or comment written in Markdown (see lib/markdown.ts for what is allowed).
 * Links within BuggyWuggy stay in the application instead of loading the page again.
 */
export function Markdown({
  text,
  attachments,
  size = 'md',
}: {
  text: string;
  attachments?: MarkdownAttachment[];
  size?: 'sm' | 'md';
}) {
  const navigate = useNavigate();
  const projectKeys = useProjectKeys().data;
  const html = useMemo(
    () => renderMarkdown(text, { attachments, projectKeys: new Set(projectKeys) }),
    [text, attachments, projectKeys],
  );

  const onClick = (e: MouseEvent<HTMLDivElement>) => {
    const link = (e.target as HTMLElement).closest('a');
    const href = link?.getAttribute('href');
    if (!link || !href || link.target || e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return;
    if (href.startsWith('/') && !href.startsWith('//') && !href.startsWith('/api/')) {
      e.preventDefault();
      navigate(href);
    }
  };

  return (
    <Typography>
      <div className={`bw-markdown bw-markdown-${size}`} onClick={onClick} dangerouslySetInnerHTML={{ __html: html }} />
    </Typography>
  );
}
