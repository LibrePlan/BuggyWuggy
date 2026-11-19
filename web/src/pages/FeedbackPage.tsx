// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import {
  Alert,
  Anchor,
  Button,
  Center,
  Container,
  CopyButton,
  Group,
  Input,
  SegmentedControl,
  Stack,
  Text,
  Textarea,
  TextInput,
  Title,
} from '@mantine/core';
import { useForm } from '@mantine/form';
import { useMediaQuery } from '@mantine/hooks';
import {
  IconBug,
  IconBulbFilled,
  IconCheck,
  IconCopy,
  IconHeart,
  IconLock,
  IconSchool,
  IconTool,
} from '@tabler/icons-react';
import { useMutation } from '@tanstack/react-query';
import { useEffect, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';
import { post } from '../api/client';
import { useFeedbackStatus, useMyself, useVersion } from '../api/queries';
import type { FeedbackRequest, FeedbackResponse, FeedbackStatus, FeedbackType } from '../api/types';
import { MarkdownEditor } from '../components/MarkdownEditor';
import { ErrorAlert, PageLoader } from '../components/QueryState';
import { fieldErrorMap } from '../lib/apiError';
import { notifyError } from '../lib/notify';
import { HelpLayout } from './HelpPages';

/**
 * Help → Send feedback: compliments, bug reports and feature requests to the BuggyWuggy
 * developers, for the users of an installation that supports the project with a donation.
 */
export function FeedbackPage() {
  const { t } = useTranslation();
  const status = useFeedbackStatus();
  const [sent, setSent] = useState<FeedbackResponse>();

  useEffect(() => {
    document.title = `${t('feedback.title')} · BuggyWuggy`;
  }, [t]);

  return (
    <HelpLayout>
      <Container size={760} my={20}>
        <Stack>
          <Title order={2}>{t('feedback.title')}</Title>
          {status.isPending ? (
            <PageLoader />
          ) : status.isError ? (
            <ErrorAlert error={status.error} />
          ) : sent ? (
            <Thanks sent={sent} onMore={() => setSent(undefined)} />
          ) : (
            <>
              <Text>{t('feedback.intro', { to: status.data.to })}</Text>
              {!status.data.allowed && <Blocked status={status.data} />}
              <FeedbackForm allowed={status.data.allowed} onSent={setSent} />
            </>
          )}
        </Stack>
      </Container>
    </HelpLayout>
  );
}

/** Why the form is disabled. */
function Blocked({ status }: { status: FeedbackStatus }) {
  const { t } = useTranslation();
  const admin = useMyself().data?.globalPermissions.includes('ADMINISTER');
  const sourceUrl = useVersion().data?.sourceUrl;
  const donationLink = admin ? (
    <Anchor component={Link} to="/admin/donation" inherit>
      {t('feedback.blocked.donationPage')}
    </Anchor>
  ) : null;
  let text: ReactNode;
  if (status.demoAccount) {
    text = t('feedback.blocked.demo');
  } else if (status.codeMissing) {
    text = (
      <>
        {t('feedback.blocked.codeMissing')} {donationLink}
      </>
    );
  } else {
    text = (
      <>
        {t(status.donation === 'EXPIRED' ? 'feedback.blocked.expired' : 'feedback.blocked.none')}{' '}
        {admin ? donationLink : t('feedback.blocked.askAdmin')}
      </>
    );
  }
  return (
    <Alert color="yellow" icon={<IconLock size={18} />} title={t('feedback.blocked.title')}>
      <Stack gap="xs">
        <Text size="sm">{text}</Text>
        {!status.demoAccount && sourceUrl && (
          <Text size="sm">
            {t('feedback.blocked.public')}{' '}
            <Anchor href={sourceUrl} target="_blank" rel="noopener noreferrer" inherit>
              {sourceUrl}
            </Anchor>
          </Text>
        )}
      </Stack>
    </Alert>
  );
}

function FeedbackForm({ allowed, onSent }: { allowed: boolean; onSent: (sent: FeedbackResponse) => void }) {
  const { t } = useTranslation();
  const form = useForm({
    initialValues: { type: 'BUG' as FeedbackType, subject: '', message: '' },
    validate: {
      subject: (v) => (v.trim() ? null : t('validation.required')),
      message: (v) => (v.trim() ? null : t('validation.required')),
    },
  });
  const send = useMutation({
    mutationFn: (request: FeedbackRequest) => post<FeedbackResponse>('/api/v1/feedback', request),
    onSuccess: (response) => {
      form.reset();
      onSent(response);
      window.scrollTo({ top: 0 });
    },
    onError: (e) => {
      const errors = fieldErrorMap(e);
      if (Object.keys(errors).length) form.setErrors(errors);
      else notifyError(e);
    },
  });
  const type = form.values.type;
  // Five options only fit next to each other on a wide screen.
  const narrow = useMediaQuery('(max-width: 62em)');
  const option = (value: FeedbackType, icon: ReactNode) => ({
    value,
    label: (
      <Center style={{ gap: 6 }}>
        {icon}
        <span>{t(`feedback.type.${value}`)}</span>
      </Center>
    ),
  });

  return (
    <form
      onSubmit={form.onSubmit((v) =>
        send.mutate({ type: v.type, subject: v.subject.trim(), message: v.message.trim() }),
      )}
    >
      <Stack>
        <Input.Wrapper label={t('feedback.typeLabel')}>
          <SegmentedControl
            display="flex"
            mt={4}
            orientation={narrow ? 'vertical' : 'horizontal'}
            disabled={!allowed}
            value={type}
            onChange={(v) => form.setFieldValue('type', v as FeedbackType)}
            data={[
              option('COMPLIMENT', <IconHeart size={16} />),
              option('BUG', <IconBug size={16} />),
              option('FEATURE', <IconBulbFilled size={16} />),
              option('SERVICE', <IconTool size={16} />),
              option('TRAINING', <IconSchool size={16} />),
            ]}
          />
        </Input.Wrapper>
        <TextInput
          label={t('feedback.subject')}
          placeholder={t(`feedback.subjectPlaceholder.${type}`)}
          required
          maxLength={200}
          disabled={!allowed}
          {...form.getInputProps('subject')}
        />
        <MarkdownEditor
          label={t('feedback.message')}
          description={t(`feedback.messageHelp.${type}`)}
          required
          minRows={8}
          maxRows={24}
          maxLength={20000}
          disabled={!allowed}
          {...form.getInputProps('message')}
        />
        <Text size="sm" c="dimmed">
          {t('feedback.privacy')}
        </Text>
        <Group>
          <Button type="submit" disabled={!allowed} loading={send.isPending}>
            {t('feedback.send')}
          </Button>
        </Group>
      </Stack>
    </form>
  );
}

/** After sending: thanks, and the mail to send by hand when BuggyWuggy cannot send mail. */
function Thanks({ sent, onMore }: { sent: FeedbackResponse; onMore: () => void }) {
  const { t } = useTranslation();
  const mailto = `mailto:${sent.to}?subject=${encodeURIComponent(sent.subject)}&body=${encodeURIComponent(sent.text)}`;
  return (
    <Stack>
      <Alert color="green" icon={<IconHeart size={18} />} title={t('feedback.thanks.title')}>
        <Stack gap="xs">
          <Text size="sm">{t('feedback.thanks.donation')}</Text>
          <Text size="sm">{t('feedback.thanks.priority')}</Text>
        </Stack>
      </Alert>
      {!sent.mailConfigured && (
        <Alert color="orange" title={t('feedback.manual.title')}>
          <Stack gap="xs">
            <Text size="sm">{t('feedback.manual.text', { to: sent.to })}</Text>
            <Group gap="md">
              <Anchor href={mailto} size="sm">
                {t('feedback.manual.mailto')}
              </Anchor>
              <CopyButton value={sent.text}>
                {({ copied, copy }) => (
                  <Button
                    size="compact-xs"
                    variant="subtle"
                    onClick={copy}
                    leftSection={copied ? <IconCheck size={14} /> : <IconCopy size={14} />}
                  >
                    {copied ? t('license.copied') : t('license.copy')}
                  </Button>
                )}
              </CopyButton>
            </Group>
            <Textarea value={sent.text} readOnly autosize minRows={8} maxRows={20} styles={{ input: { fontFamily: 'monospace' } }} />
          </Stack>
        </Alert>
      )}
      <Group>
        <Button variant="default" onClick={onMore}>
          {t('feedback.more')}
        </Button>
      </Group>
    </Stack>
  );
}
