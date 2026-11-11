/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import java.time.Clock;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CommonConfiguration {

    /**
     * All "now" timestamps come from this clock, so tests can control time. It ticks in
     * microseconds, the precision of PostgreSQL timestamps, so a value returned right after a
     * change is identical to the value read back later.
     */
    @Bean
    Clock clock() {
        return Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("BuggyWuggy API")
                .version("v1")
                .license(new License().name("AGPL-3.0-or-later").url("https://www.gnu.org/licenses/agpl-3.0.html")));
    }
}
