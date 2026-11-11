// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { ApiError, toApiError } from '../lib/apiError';
import { readCookie } from '../lib/cookies';

const CSRF_COOKIE = 'XSRF-TOKEN';
const CSRF_HEADER = 'X-XSRF-TOKEN';

type Method = 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';
export type Query = Record<string, string | number | boolean | undefined | null>;

/**
 * Calls the BuggyWuggy API. State-changing requests carry the CSRF token from the
 * {@code XSRF-TOKEN} cookie; if there is no cookie yet, it is obtained first.
 * Errors are thrown as {@link ApiError}.
 */
export async function api<T>(method: Method, path: string, options: { body?: unknown; query?: Query } = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET') headers[CSRF_HEADER] = await csrfToken();

  let response: Response;
  try {
    response = await fetch(withQuery(path, options.query), {
      method,
      headers,
      credentials: 'same-origin',
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    });
  } catch {
    throw new ApiError(0, 'The server cannot be reached.', 'network');
  }

  if (response.status === 204) return undefined as T;
  const body: unknown = (response.headers.get('Content-Type') ?? '').includes('json')
    ? await response.json().catch(() => undefined)
    : undefined;
  if (!response.ok) throw toApiError(response.status, body);
  return body as T;
}

export const get = <T>(path: string, query?: Query) => api<T>('GET', path, { query });
export const post = <T>(path: string, body?: unknown) => api<T>('POST', path, { body });
export const patch = <T>(path: string, body: unknown) => api<T>('PATCH', path, { body });
export const put = <T>(path: string, body?: unknown) => api<T>('PUT', path, { body });
export const del = <T = void>(path: string) => api<T>('DELETE', path);

async function csrfToken(): Promise<string> {
  const existing = readCookie(document.cookie, CSRF_COOKIE);
  if (existing) return existing;
  // Loading the token makes the server set the cookie.
  const response = await fetch('/api/v1/auth/csrf', { credentials: 'same-origin' });
  const fromCookie = readCookie(document.cookie, CSRF_COOKIE);
  if (fromCookie) return fromCookie;
  const body = (await response.json().catch(() => ({}))) as { token?: string };
  return body.token ?? '';
}

export function withQuery(path: string, query?: Query): string {
  if (!query) return path;
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined && value !== null && value !== '') params.set(key, String(value));
  }
  const qs = params.toString();
  return qs ? `${path}?${qs}` : path;
}

/** Path segment encoding for keys and ids. */
export const seg = (value: string | number) => encodeURIComponent(String(value));
