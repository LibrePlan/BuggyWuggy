// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { describe, expect, it } from 'vitest';
import { renderMarkdown } from './markdown';

const attachments = {
  attachments: [
    { id: 7, fileName: 'shot.png', mediaType: 'image/png' },
    { id: 8, fileName: 'log file.txt', mediaType: 'text/plain' },
  ],
};
const projects = { projectKeys: new Set(['BUG', 'UTF']) };

describe('markdown', () => {
  it('keeps plain text as it was: line breaks stay', () => {
    expect(renderMarkdown('first line\nsecond line')).toBe('<p>first line<br>\nsecond line</p>\n');
  });

  it('formats GitHub flavoured Markdown', () => {
    const html = renderMarkdown('**bold** _it_ ~~gone~~ `code`\n\n| a | b |\n|---|---|\n| 1 | 2 |');
    expect(html).toContain('<strong>bold</strong>');
    expect(html).toContain('<em>it</em>');
    expect(html).toContain('<s>gone</s>');
    expect(html).toContain('<code>code</code>');
    expect(html).toContain('<table>');
  });

  it('shows HTML as text', () => {
    const html = renderMarkdown('<script>alert(1)</script> <img src=x onerror=alert(1)>');
    expect(html).not.toContain('<script');
    expect(html).not.toContain('<img');
    expect(html).toContain('&lt;script&gt;');
  });

  it('allows only safe links, external ones in a new tab', () => {
    expect(renderMarkdown('[x](javascript:alert(1))')).not.toContain('href');
    expect(renderMarkdown('[x](data:text/html,hi)')).not.toContain('href');
    expect(renderMarkdown('[x](//evil.example/)')).not.toContain('href');
    const external = renderMarkdown('see https://example.org/page');
    expect(external).toContain('<a href="https://example.org/page" target="_blank" rel="noopener noreferrer nofollow">');
    expect(renderMarkdown('[issues](/issues)')).toContain('<a href="/issues">');
  });

  it('links issue keys, but not in code or links', () => {
    expect(renderMarkdown('Fixed by BUG-12, see BUG-3.', projects)).toBe(
      '<p>Fixed by <a href="/browse/BUG-12" class="bw-issue-key">BUG-12</a>, see ' +
        '<a href="/browse/BUG-3" class="bw-issue-key">BUG-3</a>.</p>\n',
    );
    expect(renderMarkdown('`BUG-12`', projects)).not.toContain('<a');
    expect(renderMarkdown('[see BUG-12](https://example.org)', projects)).not.toContain('/browse/');
    expect(renderMarkdown('BUG-0 and bug-12 and SHA-256', projects)).not.toContain('<a');
    expect(renderMarkdown('BUG-12')).not.toContain('<a');
  });

  it('shows images of the issue only', () => {
    const own = renderMarkdown('![screen](attachment:Shot.png)', attachments);
    expect(own).toContain('<img src="/api/v1/attachments/7/content" alt="screen" loading="lazy">');
    const external = renderMarkdown('![tracker](https://evil.example/pixel.gif)', attachments);
    expect(external).not.toContain('<img');
    expect(external).toContain('<a href="https://evil.example/pixel.gif"');
    expect(renderMarkdown('![x](attachment:missing.png)', attachments)).toContain('<span class="bw-missing"');
    expect(renderMarkdown('![log](attachment:log%20file.txt)', attachments)).not.toContain('<img');
  });

  it('links attachments by name', () => {
    expect(renderMarkdown('[the log](<attachment:log file.txt>)', attachments)).toContain(
      '<a href="/api/v1/attachments/8/content" target="_blank">the log</a>',
    );
    expect(renderMarkdown('[gone](attachment:nope.txt)', attachments)).toContain('<a class="bw-missing" title="Attachment not found">');
  });

  it('renders task lists', () => {
    const html = renderMarkdown('- [ ] to do\n- [x] done');
    expect(html).toContain('<li class="bw-task"><input type="checkbox" class="bw-task-box" disabled> to do</li>');
    expect(html).toContain('disabled checked> done');
  });
});
