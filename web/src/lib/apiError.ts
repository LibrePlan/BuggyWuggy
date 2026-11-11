// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

export interface FieldError {
  field: string;
  message: string;
}

/**
 * An error answer of the API: an RFC 9457 problem detail. {@code code} is the stable message
 * key (e.g. {@code issue.version-conflict}); {@code detail} is the human-readable message.
 */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string | undefined;
  readonly fieldErrors: FieldError[];

  constructor(status: number, message: string, code?: string, fieldErrors: FieldError[] = []) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
    this.fieldErrors = fieldErrors;
  }
}

/** Builds an {@link ApiError} from a response status and its (possibly non-JSON) body. */
export function toApiError(status: number, body: unknown): ApiError {
  if (body && typeof body === 'object') {
    const p = body as Record<string, unknown>;
    const message = str(p.detail) ?? str(p.title) ?? `HTTP ${status}`;
    const errors = Array.isArray(p.errors)
      ? p.errors.flatMap((e) =>
          e && typeof e === 'object' && str((e as FieldError).field) !== undefined
            ? [{ field: (e as FieldError).field, message: String((e as FieldError).message) }]
            : [],
        )
      : [];
    return new ApiError(status, message, str(p.code), errors);
  }
  return new ApiError(status, `HTTP ${status}`);
}

/** Maps field errors to the shape Mantine forms expect ({@code form.setErrors}). */
export function fieldErrorMap(error: unknown): Record<string, string> {
  if (!(error instanceof ApiError)) return {};
  return Object.fromEntries(error.fieldErrors.map((e) => [e.field, e.message]));
}

function str(value: unknown): string | undefined {
  return typeof value === 'string' && value.length > 0 ? value : undefined;
}
