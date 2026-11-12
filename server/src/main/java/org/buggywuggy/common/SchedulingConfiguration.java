/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.common;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background jobs (sending mail). Tests switch them off and run the jobs themselves. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "buggywuggy.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfiguration {
}
