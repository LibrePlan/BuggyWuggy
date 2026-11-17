// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Stack } from '@mantine/core';
import mark from '../assets/logo-mark.png';
import markSmall from '../assets/logo-mark-small.png';
import wordmark from '../assets/logo-wordmark.png';

/**
 * The full logo: the furry B with the name below it. The name is dark brown; in the dark
 * colour scheme it is turned light (styles.css), so it stays readable.
 */
export function Logo({ width }: { width: number }) {
  return (
    <Stack gap={width / 14} align="center" w={width} role="img" aria-label="BuggyWuggy">
      <img src={mark} alt="" width={width * 0.8} height={Math.round(width * 0.8 * (487 / 440))} />
      <img src={wordmark} alt="" width={width} height={Math.round(width * (90 / 520))} className="bw-logo-wordmark" />
    </Stack>
  );
}

/** Only the B, for the top left corner of the application. */
export function LogoMark({ height }: { height: number }) {
  return <img src={markSmall} alt="" height={height} width={Math.round(height * (65 / 72))} />;
}
