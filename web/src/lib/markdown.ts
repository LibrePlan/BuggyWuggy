// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import MarkdownIt from 'markdown-it';
import type Token from 'markdown-it/lib/token.mjs';

/**
 * Markdown of issue descriptions and comments, as HTML. The rules (the server renders mails
 * the same way, see org.buggywuggy.mail.IssueMarkdown):
 *
 * - CommonMark with GitHub's tables, strikethrough and task lists; a single line break is a
 *   line break, so plain text written before Markdown still looks the same;
 * - no HTML of its own: it is shown as text;
 * - links only to http(s), mailto and pages of BuggyWuggy itself; bare URLs become links;
 * - issue keys such as BUG-12 link to the issue, when there is a project BUG (so UTF-8 does not);
 * - images only from the issue's own attachments, {@code ![screenshot](attachment:shot.png)}:
 *   other images would tell their server who reads the issue. They become a link instead.
 */

/** An attachment of the issue, for {@code attachment:file name} references. */
export interface MarkdownAttachment {
  id: number;
  fileName: string;
  mediaType: string;
}

interface Env {
  attachments: MarkdownAttachment[];
  projectKeys: ReadonlySet<string>;
}

/** What a text can refer to: the issue's attachments, and the projects for issue keys. */
export interface MarkdownContext {
  attachments?: MarkdownAttachment[];
  /** Upper case. */
  projectKeys?: ReadonlySet<string>;
}

const ISSUE_KEYS = /\b[A-Z][A-Z0-9_]{1,9}-[1-9][0-9]*\b/g;
const HAS_ISSUE_KEY = new RegExp(ISSUE_KEYS.source);
const ATTACHMENT = 'attachment:';

const md = new MarkdownIt({ html: false, linkify: true, breaks: true });

md.validateLink = (url) => {
  const u = url.trim().toLowerCase();
  return (
    /^(https?:|mailto:)/.test(u) ||
    (u.startsWith('/') && !u.startsWith('//')) ||
    u.startsWith('#') ||
    u.startsWith(ATTACHMENT)
  );
};

function attachmentFor(env: Env, reference: string): MarkdownAttachment | undefined {
  let name = reference.slice(ATTACHMENT.length);
  try {
    name = decodeURIComponent(name);
  } catch {
    // keep it as written
  }
  const wanted = name.trim().toLowerCase();
  // The newest of equally named attachments: the list is oldest first.
  return [...env.attachments].reverse().find((a) => a.fileName.toLowerCase() === wanted);
}

const contentUrl = (a: MarkdownAttachment) => `/api/v1/attachments/${a.id}/content`;
const OWN_CONTENT = /^\/api\/v1\/attachments\/\d+\/content$/;
const escapeHtml = md.utils.escapeHtml;

// Issue keys in text become links, except in links and code.
md.core.ruler.after('linkify', 'issue-keys', (state) => {
  const projects = (state.env as Env).projectKeys;
  if (!projects.size) return;
  for (const block of state.tokens) {
    if (block.type !== 'inline' || !block.children) continue;
    const out: Token[] = [];
    let inLink = 0;
    for (const token of block.children) {
      if (token.type === 'link_open') inLink++;
      if (token.type === 'link_close') inLink--;
      if (token.type !== 'text' || inLink > 0 || !HAS_ISSUE_KEY.test(token.content)) {
        out.push(token);
        continue;
      }
      let last = 0;
      for (const match of token.content.matchAll(ISSUE_KEYS)) {
        if (!projects.has(match[0].slice(0, match[0].lastIndexOf('-')))) continue;
        const at = match.index;
        if (at > last) out.push(text(state, token.content.slice(last, at)));
        const open = new state.Token('link_open', 'a', 1);
        open.attrs = [
          ['href', `/browse/${match[0]}`],
          ['class', 'bw-issue-key'],
        ];
        out.push(open, text(state, match[0]), new state.Token('link_close', 'a', -1));
        last = at + match[0].length;
      }
      if (last === 0) out.push(token);
      else if (last < token.content.length) out.push(text(state, token.content.slice(last)));
    }
    block.children = out;
  }
});

function text(state: { Token: typeof Token }, content: string): Token {
  const t = new state.Token('text', '', 0);
  t.content = content;
  return t;
}

// Task lists: "- [ ] to do" and "- [x] done".
md.core.ruler.after('inline', 'task-lists', (state) => {
  const tokens = state.tokens;
  for (let i = 2; i < tokens.length; i++) {
    const inline = tokens[i]!;
    if (inline.type !== 'inline' || tokens[i - 1]!.type !== 'paragraph_open' || tokens[i - 2]!.type !== 'list_item_open') {
      continue;
    }
    const first = inline.children?.[0];
    const match = first?.type === 'text' ? /^\[([ xX])\]\s/.exec(first.content) : null;
    if (!first || !match) continue;
    first.content = first.content.slice(match[0].length);
    const box = new state.Token('html_inline', '', 0);
    box.content = `<input type="checkbox" class="bw-task-box" disabled${match[1] === ' ' ? '' : ' checked'}> `;
    inline.children!.unshift(box);
    tokens[i - 2]!.attrJoin('class', 'bw-task');
  }
});

const defaultLinkOpen = md.renderer.rules.link_open ?? ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options));

md.renderer.rules.link_open = (tokens, idx, options, env: Env, self) => {
  const token = tokens[idx]!;
  const href = token.attrGet('href') ?? '';
  if (href.toLowerCase().startsWith(ATTACHMENT)) {
    const attachment = attachmentFor(env, href);
    if (attachment) {
      token.attrSet('href', contentUrl(attachment));
      token.attrSet('target', '_blank');
    } else {
      token.attrs = (token.attrs ?? []).filter(([name]) => name !== 'href');
      token.attrJoin('class', 'bw-missing');
      token.attrSet('title', 'Attachment not found');
    }
  } else if (/^(https?:|mailto:)/i.test(href)) {
    token.attrSet('target', '_blank');
    token.attrSet('rel', 'noopener noreferrer nofollow');
  }
  return defaultLinkOpen(tokens, idx, options, env, self);
};

md.renderer.rules.image = (tokens, idx, options, env: Env, self) => {
  const token = tokens[idx]!;
  const src = token.attrGet('src') ?? '';
  const alt = self.renderInlineAsText(token.children ?? [], options, env);
  const attachment = src.toLowerCase().startsWith(ATTACHMENT) ? attachmentFor(env, src) : undefined;
  const own = attachment ? contentUrl(attachment) : OWN_CONTENT.test(src) ? src : null;
  if (own && (!attachment || attachment.mediaType.startsWith('image/'))) {
    const title = token.attrGet('title');
    return (
      `<a href="${escapeHtml(own)}" target="_blank" class="bw-image-link">` +
      `<img src="${escapeHtml(own)}" alt="${escapeHtml(alt)}"${title ? ` title="${escapeHtml(title)}"` : ''} loading="lazy"></a>`
    );
  }
  // Not an image of this issue: a link to it, so nothing is loaded from elsewhere.
  const label = escapeHtml(alt || src);
  if (/^https?:/i.test(src)) {
    return `<a href="${escapeHtml(src)}" target="_blank" rel="noopener noreferrer nofollow" class="bw-image-ref">${label}</a>`;
  }
  return `<span class="bw-missing" title="Attachment not found">${label}</span>`;
};

/** The HTML of {@code markdown}; safe to insert in the page. */
export function renderMarkdown(markdown: string, context: MarkdownContext = {}): string {
  const env: Env = { attachments: context.attachments ?? [], projectKeys: context.projectKeys ?? new Set() };
  return md.render(markdown, env);
}
