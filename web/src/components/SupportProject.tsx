// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Alert, Anchor, Box, Collapse, Flex, Image, Paper, Stack, Text, Title } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { IconHeart } from '@tabler/icons-react';
import { useState, type ReactNode } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import type { SupportStatus } from '../api/types';

/**
 * Why supporting BuggyWuggy matters and whether this installation does, on the login page and
 * under Help → About. {@code collapsible}: the explanation is behind "more information".
 * {@code wide}: for a wide column (the login page): the explanation left of the comic, on
 * screens wide enough for that.
 */
export function SupportProject({
  status,
  requestLink,
  hint,
  defaultOpen = false,
  collapsible = true,
  wide = false,
  thanksOnly = false,
}: {
  status: SupportStatus;
  requestLink?: ReactNode;
  hint?: string;
  defaultOpen?: boolean;
  collapsible?: boolean;
  wide?: boolean;
  /** For a supporter (status ACTIVE) only the thank-you line, nothing that asks for a donation. */
  thanksOnly?: boolean;
}) {
  const { t } = useTranslation();
  const [moreInfo, { toggle: toggleMoreInfo }] = useDisclosure(defaultOpen);
  if (thanksOnly && status === 'ACTIVE') {
    // A supporter is not asked again: only the thank-you, as large as the title would be.
    return (
      <Box mt="xl">
        <SupportStatusLine status={status} large />
      </Box>
    );
  }
  const texts = (
    <>
      <Text size="sm">{t('donate.freedom')}</Text>
      <Text size="sm">{t('donate.people')}</Text>
      <Text size="sm">{t('donate.continuity')}</Text>
    </>
  );
  const explanation = (
    <Paper withBorder p="md" radius="md">
      {wide ? (
        <Flex direction={{ base: 'column', sm: 'row' }} gap="lg" align="center">
          <Stack gap="sm" flex={1}>
            {texts}
          </Stack>
          <Box w={{ base: '100%', sm: 385 }} style={{ flexShrink: 0 }}>
            <XkcdDependency />
          </Box>
        </Flex>
      ) : (
        <Stack gap="sm">
          {texts}
          <XkcdDependency />
        </Stack>
      )}
    </Paper>
  );
  return (
    <Stack gap="sm" mt="xl">
      <Title order={2} ta="center">
        {t('donate.title')}
      </Title>
      <Text size="sm" ta="center" maw={640} mx="auto">
        {t('donate.intro')}
      </Text>
      {requestLink}
      {hint && (
        <Text size="sm" c="dimmed" ta="center">
          {hint}
        </Text>
      )}
      <SupportStatusLine status={status} />
      {collapsible ? (
        <>
          <Anchor component="button" type="button" onClick={toggleMoreInfo} aria-expanded={moreInfo} ta="center">
            {moreInfo ? t('donate.lessInfo') : t('donate.moreInfo')}
          </Anchor>
          <Collapse expanded={moreInfo}>{explanation}</Collapse>
        </>
      ) : (
        explanation
      )}
    </Stack>
  );
}

const SUPPORT_COLORS: Record<SupportStatus, string> = { NONE: 'blue', ACTIVE: 'green', EXPIRED: 'orange' };

/** Whether this installation supports the project: never, with a valid donation code, or no longer. */
function SupportStatusLine({ status, large = false }: { status: SupportStatus; large?: boolean }) {
  const { t } = useTranslation();
  const key = status.toLowerCase() as Lowercase<SupportStatus>;
  return (
    <Alert
      color={SUPPORT_COLORS[status]}
      variant="light"
      icon={<IconHeart size={large ? 32 : undefined} />}
      py={large ? 'md' : 'xs'}
      styles={large ? { icon: { width: 32, height: 32 } } : undefined}
    >
      <Text size="sm" fz={large ? 'var(--mantine-h2-font-size)' : undefined} lh={large ? 1.3 : undefined}>
        <Text span inherit fw={700}>
          {t(`donate.status.${key}.title`)}
        </Text>
        {' — '}
        {t(`donate.status.${key}.text`)}
      </Text>
    </Alert>
  );
}

const XKCD_COMIC = 'https://xkcd.com/2347/';

/**
 * xkcd 2347 "Dependency" by Randall Munroe, CC BY-NC 2.5. Not part of this repository (its
 * NonCommercial license does not fit the AGPL of BuggyWuggy): the server downloads it once
 * from imgs.xkcd.com and serves it itself, so visitors never contact xkcd.com. Until it has
 * been downloaded (or when downloading is switched off), only the attribution is shown.
 */
function XkcdDependency() {
  const { t } = useTranslation();
  const [missing, setMissing] = useState(false);
  return (
    <Box component="figure" m={0}>
      {!missing && (
        <Anchor href={XKCD_COMIC} target="_blank" rel="noopener noreferrer" display="block">
          <Image
            src="/api/v1/donation/comic.png"
            srcSet="/api/v1/donation/comic-2x.png 2x"
            alt={t('donate.comicAlt')}
            title={t('donate.comicAlt')}
            w="100%"
            maw={385}
            mx="auto"
            loading="lazy"
            onError={() => setMissing(true)}
          />
        </Anchor>
      )}
      <Text component="figcaption" size="xs" c="dimmed" ta="center" mt={4}>
        <Trans
          i18nKey="donate.comicAttribution"
          components={{
            work: <Anchor href={XKCD_COMIC} target="_blank" rel="noopener noreferrer" inherit />,
            license: (
              <Anchor
                href="https://creativecommons.org/licenses/by-nc/2.5/"
                target="_blank"
                rel="license noopener noreferrer"
                inherit
              />
            ),
          }}
        />
      </Text>
    </Box>
  );
}
