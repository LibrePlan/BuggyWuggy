// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Anchor, Tooltip } from '@mantine/core';
import { IconHelpCircle } from '@tabler/icons-react';

/**
 * A small circled question mark next to an action, that opens its section of the user guide.
 * Actions that are not available are shown disabled with this hint (never hidden), and the
 * tooltip says why.
 */
export function HelpHint({ anchor, label }: { anchor: string; label: string }) {
  return (
    <Tooltip label={label} multiline maw={320} openDelay={200}>
      <Anchor
        href={`/help/user-guide#${anchor}`}
        target="_blank"
        c="dimmed"
        aria-label={label}
        display="flex"
      >
        <IconHelpCircle size={18} />
      </Anchor>
    </Tooltip>
  );
}
