// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import {
  Alert,
  Anchor,
  Button,
  Code,
  CopyButton,
  Group,
  NumberInput,
  Paper,
  Select,
  SimpleGrid,
  Stack,
  Table,
  Text,
  Textarea,
  TextInput,
  Title,
} from '@mantine/core';
import { useDebouncedValue } from '@mantine/hooks';
import { IconCheck, IconCopy, IconHeart } from '@tabler/icons-react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { post } from '../../api/client';
import { keys, useLicenseCountries, useLicenseQuote, useLicenseStatus } from '../../api/queries';
import type { LicenseCountry, LicenseQuote, LicenseStatus, QuotationRequest, QuotationResponse } from '../../api/types';
import { ErrorAlert, PageLoader } from '../../components/QueryState';
import { fieldErrorMap } from '../../lib/apiError';
import { formatDate, formatDateTime } from '../../lib/format';
import { notifyError } from '../../lib/notify';

export function formatMoney(amount: number, currency: string, language: string) {
  try {
    return new Intl.NumberFormat(language, { style: 'currency', currency }).format(amount);
  } catch {
    return `${amount} ${currency}`;
  }
}

/**
 * The annual donation of this installation: the suggested contribution per country (based on the
 * Big Mac index), a quotation request mailed to sales@buggywuggy.org, and entering the donation
 * code.
 */
export function LicensePage() {
  const { t } = useTranslation();
  const status = useLicenseStatus();
  if (status.isPending) return <PageLoader />;
  if (status.isError) return <ErrorAlert error={status.error} />;
  return (
    <Stack gap="xl">
      <LicenseState status={status.data} />
      <QuotationForm status={status.data} />
      <ActivateForm status={status.data} />
      <Text size="xs" c="dimmed">
        {t('license.attribution')}
      </Text>
    </Stack>
  );
}

function LicenseState({ status }: { status: LicenseStatus }) {
  const { t, i18n } = useTranslation();
  return status.licensed ? (
    <Alert color="green" icon={<IconHeart />} title={t('license.thanks')}>
      {t('license.validUntil', { date: formatDate(status.validUntil!, i18n.language) })}
    </Alert>
  ) : (
    <Alert color="blue" icon={<IconHeart />} title={t('license.why')}>
      {t('license.whyText')}
    </Alert>
  );
}

function QuotationForm({ status }: { status: LicenseStatus }) {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const countries = useLicenseCountries();
  const [country, setCountry] = useState<LicenseCountry | null>(null);
  const [users, setUsers] = useState<number | null>(Math.max(status.userCount, 1));
  const [debouncedUsers] = useDebouncedValue(users, 300);
  const quote = useLicenseQuote(country?.code ?? null, debouncedUsers);
  const [company, setCompany] = useState('');
  const [contact, setContact] = useState('');
  const [email, setEmail] = useState('');
  const [phone, setPhone] = useState('');
  const [sent, setSent] = useState<QuotationResponse | null>(null);

  const send = useMutation({
    mutationFn: (request: QuotationRequest) => post<QuotationResponse>('/api/v1/admin/license/requests', request),
    onSuccess: (response) => {
      setSent(response);
      void queryClient.invalidateQueries({ queryKey: keys.license });
    },
    onError: notifyError,
  });
  const errors = fieldErrorMap(send.error);

  function chooseCountry(code: string | null) {
    const chosen = countries.data?.find((c) => c.code === code) ?? null;
    // The country's dialling code, unless a number of another country was typed already.
    const previous = country?.dialCode ?? '';
    if (chosen && (phone.trim() === '' || phone.trim() === previous)) setPhone(`${chosen.dialCode} `);
    setCountry(chosen);
  }

  if (countries.isPending) return <PageLoader />;
  if (countries.isError) return <ErrorAlert error={countries.error} />;
  const valid = country && users && users >= 1 && company.trim() && contact.trim() && /^\S+@\S+\.\S+$/.test(email.trim());

  return (
    <Stack>
      <Title order={3}>{t('license.quotation.title')}</Title>
      <Text size="sm" c="dimmed">
        {t('license.quotation.intro')}
      </Text>
      <SimpleGrid cols={{ base: 1, sm: 2 }}>
        <Select
          label={t('license.quotation.country')}
          placeholder={t('license.quotation.countryPlaceholder')}
          data={countries.data.map((c) => ({ value: c.code, label: c.name }))}
          value={country?.code ?? null}
          onChange={chooseCountry}
          searchable
          nothingFoundMessage={t('common.nothingFound')}
          required
        />
        <NumberInput
          label={t('license.quotation.users')}
          description={t('license.quotation.usersHelp', { count: status.userCount })}
          value={users ?? ''}
          onChange={(v) => setUsers(typeof v === 'number' ? v : null)}
          min={1}
          max={1_000_000}
          allowDecimal={false}
          required
        />
      </SimpleGrid>
      {country && quote.data && <QuoteView quote={quote.data} language={i18n.language} />}
      {quote.isError && <ErrorAlert error={quote.error} />}
      <SimpleGrid cols={{ base: 1, sm: 2 }}>
        <TextInput label={t('license.quotation.company')} value={company} onChange={(e) => setCompany(e.currentTarget.value)}
          error={errors.companyName} maxLength={200} required />
        <TextInput label={t('license.quotation.contact')} value={contact} onChange={(e) => setContact(e.currentTarget.value)}
          error={errors.contactName} maxLength={200} required />
        <TextInput label={t('license.quotation.email')} type="email" value={email}
          onChange={(e) => setEmail(e.currentTarget.value)} error={errors.contactEmail} maxLength={254} required />
        <TextInput label={t('license.quotation.phone')} type="tel" value={phone}
          onChange={(e) => setPhone(e.currentTarget.value)} error={errors.contactPhone} maxLength={50} />
      </SimpleGrid>
      <Group>
        <Button
          disabled={!valid}
          loading={send.isPending}
          onClick={() =>
            send.mutate({
              country: country!.code,
              users: users!,
              companyName: company.trim(),
              contactName: contact.trim(),
              contactEmail: email.trim(),
              contactPhone: phone.trim() === country!.dialCode ? undefined : phone.trim() || undefined,
            })
          }
        >
          {t('license.quotation.send')}
        </Button>
      </Group>
      {sent && <SentNotice sent={sent} />}
    </Stack>
  );
}

function QuoteView({ quote, language }: { quote: LicenseQuote; language: string }) {
  const { t } = useTranslation();
  return (
    <Paper withBorder p="md">
      <Stack gap={4}>
        <Text size="sm">
          {t('license.quote.perUser', { price: formatMoney(quote.pricePerUserMonth, quote.currency, language) })}
        </Text>
        <Text fw={700} size="xl">
          {t('license.quote.yearly', {
            amount: formatMoney(quote.yearlyTotal, quote.currency, language),
            count: quote.users,
          })}
        </Text>
        <Text size="sm" c="dimmed">
          {quote.currencyName} ({quote.currency})
        </Text>
        <Text size="xs" c="dimmed">
          {quote.bigMacPrice != null
            ? t('license.quote.calculation', {
                base: formatMoney(quote.basePricePerUserEur, 'EUR', language),
                bigMac: formatMoney(quote.bigMacPrice, quote.currency, language),
                reference: formatMoney(quote.referenceBigMacPrice!, 'EUR', language),
                country: quote.countryName,
                date: quote.indexDate,
              })
            : t('license.quote.noIndex', { base: formatMoney(quote.basePricePerUserEur, 'EUR', language) })}
        </Text>
      </Stack>
    </Paper>
  );
}

function SentNotice({ sent }: { sent: QuotationResponse }) {
  const { t } = useTranslation();
  const mailto = `mailto:${sent.to}?subject=${encodeURIComponent(sent.subject)}&body=${encodeURIComponent(sent.text)}`;
  return sent.mailConfigured ? (
    <Alert color="green" title={t('license.sent.title')}>
      <Stack gap="xs">
        <Text size="sm">{t('license.sent.text', { to: sent.to })}</Text>
        <Challenge value={sent.challenge} />
      </Stack>
    </Alert>
  ) : (
    <Alert color="orange" title={t('license.sent.queuedTitle')}>
      <Stack gap="xs">
        <Text size="sm">{t('license.sent.queued', { to: sent.to })}</Text>
        <Anchor href={mailto} size="sm">
          {t('license.sent.mailto')}
        </Anchor>
        <Textarea value={sent.text} readOnly autosize minRows={6} styles={{ input: { fontFamily: 'monospace' } }} />
        <Challenge value={sent.challenge} />
      </Stack>
    </Alert>
  );
}

function Challenge({ value }: { value: string }) {
  const { t } = useTranslation();
  return (
    <Group gap="xs">
      <Text size="sm">{t('license.challenge')}</Text>
      <Code>{value}</Code>
      <CopyButton value={value}>
        {({ copied, copy }) => (
          <Button size="compact-xs" variant="subtle" onClick={copy} leftSection={copied ? <IconCheck size={14} /> : <IconCopy size={14} />}>
            {copied ? t('license.copied') : t('license.copy')}
          </Button>
        )}
      </CopyButton>
    </Group>
  );
}

function ActivateForm({ status }: { status: LicenseStatus }) {
  const { t, i18n } = useTranslation();
  const queryClient = useQueryClient();
  const [code, setCode] = useState('');
  const activate = useMutation({
    mutationFn: () => post<LicenseStatus>('/api/v1/admin/license', { code: code.trim() }),
    onSuccess: (result) => {
      queryClient.setQueryData(keys.license, result);
      void queryClient.invalidateQueries({ queryKey: keys.donation });
      setCode('');
    },
  });

  return (
    <Stack>
      <Title order={3}>{t('license.activate.title')}</Title>
      <Text size="sm" c="dimmed">
        {t('license.activate.intro')}
      </Text>
      {status.requests.length > 0 && (
        <Table verticalSpacing="xs">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>{t('license.challenge')}</Table.Th>
              <Table.Th>{t('license.requested')}</Table.Th>
              <Table.Th visibleFrom="sm">{t('license.quotation.company')}</Table.Th>
              <Table.Th ta="right">{t('license.yearlyTotal')}</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {status.requests.map((r) => (
              <Table.Tr key={r.challenge}>
                <Table.Td>
                  <Code>{r.challenge}</Code>
                </Table.Td>
                <Table.Td>{formatDateTime(r.createdAt, i18n.language)}</Table.Td>
                <Table.Td visibleFrom="sm">{r.companyName}</Table.Td>
                <Table.Td ta="right">{formatMoney(r.yearlyTotal, r.currency, i18n.language)}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      {activate.isError && <ErrorAlert error={activate.error} />}
      {activate.isSuccess && (
        <Alert color="green">{t('license.validUntil', { date: formatDate(activate.data.validUntil!, i18n.language) })}</Alert>
      )}
      <Group align="flex-end">
        <TextInput
          label={t('license.activate.code')}
          placeholder="BWL1-…"
          value={code}
          onChange={(e) => setCode(e.currentTarget.value)}
          style={{ flex: 1 }}
          maw={640}
        />
        <Button disabled={!code.trim()} loading={activate.isPending} onClick={() => activate.mutate()}>
          {t('license.activate.submit')}
        </Button>
      </Group>
    </Stack>
  );
}
