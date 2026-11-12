// BuggyWuggy — open source, self-hosted issue tracker
// Copyright (C) 2026 The BuggyWuggy contributors
// SPDX-License-Identifier: AGPL-3.0-or-later

import { notifications } from '@mantine/notifications';
import i18n from '../i18n';

/** Shows a failed request as a red notification. */
export function notifyError(error: unknown) {
  notifications.show({
    color: 'red',
    title: i18n.t('error.title'),
    message: error instanceof Error ? error.message : i18n.t('error.unknown'),
  });
}

export function notifySaved(message = i18n.t('common.saved')) {
  notifications.show({ color: 'green', message });
}

/** The minimum password length the server enforces. */
export const PASSWORD_MIN = 12;
