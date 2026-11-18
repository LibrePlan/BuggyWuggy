// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { Anchor, AppShell, Box, Container, Group, NavLink, ScrollArea, Stack, Text, Typography } from '@mantine/core';
import { useMediaQuery } from '@mantine/hooks';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router';
import { useDonation, useHelpPage, useMyself, useVersion } from '../api/queries';
import { HelpSearch } from '../components/HelpSearch';
import { Logo, LogoMark } from '../components/Logo';
import { ErrorAlert, PageLoader } from '../components/QueryState';
import { SupportProject } from '../components/SupportProject';

/** The Help menu's pages open in a tab of their own: a header of their own, no app navigation. */
export function HelpLayout({ children }: { children: ReactNode }) {
  const { t } = useTranslation();
  const myself = useMyself().data;
  const admin = myself?.globalPermissions.includes('ADMINISTER');
  return (
    <AppShell header={{ height: 56 }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          <Anchor component={Link} to="/" underline="never" c="inherit">
            <Group gap={8} wrap="nowrap">
              <LogoMark height={36} />
              <Text fw={700} size="lg">
                {t('help.title')}
              </Text>
            </Group>
          </Anchor>
          <Group gap="lg" wrap="nowrap" visibleFrom="sm">
            {myself && (
              <Anchor component={Link} to="/help/user-guide" c="inherit" fw={500}>
                {t('help.userGuide')}
              </Anchor>
            )}
            {admin && (
              <Anchor component={Link} to="/help/admin-guide" c="inherit" fw={500}>
                {t('help.adminGuide')}
              </Anchor>
            )}
            <Anchor component={Link} to="/help/license" c="inherit" fw={500}>
              {t('help.license')}
            </Anchor>
            <Anchor component={Link} to="/help/about" c="inherit" fw={500}>
              {t('help.about')}
            </Anchor>
            {myself && (
              <Anchor component={Link} to="/help/feedback" c="inherit" fw={500}>
                {t('help.feedback')}
              </Anchor>
            )}
          </Group>
        </Group>
      </AppShell.Header>
      <AppShell.Main>{children}</AppShell.Main>
    </AppShell>
  );
}

/** A guide or the license, as rendered by the server from the documentation. */
export function HelpPage({ page }: { page?: string }) {
  const params = useParams();
  const id = page ?? params.page ?? '';
  const help = useHelpPage(id);
  const { hash } = useLocation();
  const navigate = useNavigate();
  const content = useRef<HTMLDivElement>(null);
  // The search is part of the address (?q=), so it survives a reload and can be shared.
  const [search] = useSearchParams();
  const [query, setQuery] = useState(() => search.get('q') ?? '');
  useEffect(() => {
    if ((search.get('q') ?? '') === query) return;
    const p = new URLSearchParams(search);
    if (query) p.set('q', query);
    else p.delete('q');
    // Keep the #anchor: setSearchParams would drop it.
    navigate({ search: p.toString() ? `?${p}` : '', hash }, { replace: true });
  }, [query, search, hash, navigate]);
  const wide = useMediaQuery('(min-width: 62em)');

  useEffect(() => {
    if (help.data) document.title = `${help.data.title} · BuggyWuggy`;
  }, [help.data]);
  // The content arrives after the browser looked for the #anchor: scroll to it now.
  useEffect(() => {
    if (help.data && hash) document.getElementById(decodeURIComponent(hash.slice(1)))?.scrollIntoView();
  }, [help.data, hash]);

  const toc = help.data?.headings.filter((h) => h.level <= 2) ?? [];
  // With a table of contents on a wide screen the search sits on top of it, and its results
  // take its place; otherwise it sits above the text.
  const inSidebar = !!wide && toc.length > 0;
  const searchBox = help.data && (
    <HelpSearch
      content={content}
      pageTitle={help.data.title}
      html={help.data.html}
      query={query}
      onQueryChange={setQuery}
      maxResultsHeight={inSidebar ? 'calc(100vh - 150px)' : '40vh'}
    />
  );
  return (
    <HelpLayout>
      {help.isPending ? (
        <PageLoader />
      ) : help.isError ? (
        <ErrorAlert error={help.error} />
      ) : (
        <Group align="flex-start" gap="xl" wrap="nowrap">
          {inSidebar && (
            <Stack w={280} miw={280} pos="sticky" top={72} gap="sm">
              {searchBox}
              {!query.trim() && (
                <ScrollArea.Autosize mah="calc(100vh - 140px)" type="hover">
                  {toc.map((h) => (
                    <NavLink
                      key={h.id}
                      component="a"
                      href={`#${h.id}`}
                      label={h.text}
                      pl={h.level === 1 ? 'xs' : 'lg'}
                      py={4}
                      fw={h.level === 1 ? 600 : undefined}
                      active={hash === `#${h.id}`}
                    />
                  ))}
                </ScrollArea.Autosize>
              )}
            </Stack>
          )}
          <Box miw={0} maw={900} flex={1} mx={inSidebar ? undefined : 'auto'}>
            {!inSidebar && <Box mb="lg">{searchBox}</Box>}
            <Typography>
              <div ref={content} className="bw-help" dangerouslySetInnerHTML={{ __html: help.data.html }} />
            </Typography>
          </Box>
        </Group>
      )}
    </HelpLayout>
  );
}

/** Name, version and commit, the source code, and the donation status: as on the login page. */
export function AboutPage() {
  const { t } = useTranslation();
  const version = useVersion().data;
  const donation = useDonation().data;
  const admin = useMyself().data?.globalPermissions.includes('ADMINISTER');

  useEffect(() => {
    document.title = `${t('help.about')} · BuggyWuggy`;
  }, [t]);

  return (
    <HelpLayout>
      <Container size={560} my={20}>
        <Stack align="center" gap="xs">
          <Logo width={200} />
          <Text c="dimmed">{t('about.tagline')}</Text>
          {version && (
            <Text size="sm">
              {version.commit
                ? t('login.versionCommit', { version: version.version, commit: version.commit })
                : t('login.version', { version: version.version })}
            </Text>
          )}
          <Text size="sm" ta="center">
            {t('about.license')}{' '}
            <Anchor component={Link} to="/help/license" inherit>
              {t('about.licenseLink')}
            </Anchor>
          </Text>
          {version && (
            <Text size="sm" ta="center">
              {t('about.source')}{' '}
              <Anchor href={version.sourceUrl} target="_blank" rel="noopener noreferrer" inherit>
                {version.sourceUrl}
              </Anchor>
            </Text>
          )}
        </Stack>
        {donation && (
          <SupportProject
            status={donation.status}
            collapsible={false}
            requestLink={
              admin ? (
                <Anchor component={Link} to="/admin/donation" size="sm" ta="center">
                  {t('donate.request')}
                </Anchor>
              ) : undefined
            }
            hint={admin ? undefined : t('about.askAdmin')}
          />
        )}
      </Container>
    </HelpLayout>
  );
}
