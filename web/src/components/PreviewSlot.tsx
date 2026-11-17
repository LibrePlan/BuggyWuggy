// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { createContext } from 'react';

/**
 * Where a {@link MarkdownEditor} below this provider shows its preview: in this element (a
 * column to the right of the editor), at the height of its text field, instead of next to the
 * text field. The element must be positioned (position: relative). Null: next to it, as usual.
 */
export const PreviewSlotContext = createContext<HTMLElement | null>(null);
