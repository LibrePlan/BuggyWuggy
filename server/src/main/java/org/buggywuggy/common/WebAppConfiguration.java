/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.common;

import java.io.IOException;
import java.time.Duration;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the web UI (built by the {@code web} module into {@code classpath:/static}).
 *
 * <p>The UI is a single-page app: any path that is not an API endpoint and not an existing
 * file is answered with {@code index.html}, so the browser's router can handle URLs such as
 * {@code /browse/BUG-12}. Hashed build assets are cached for a year; {@code index.html} is
 * never cached, so a new release is picked up immediately.
 */
@Configuration(proxyBeanMethods = false)
public class WebAppConfiguration implements WebMvcConfigurer {

    private static final String STATIC = "classpath:/static/";

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations(STATIC + "assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());

        registry.addResourceHandler("/**")
                .addResourceLocations(STATIC)
                .setCacheControl(CacheControl.noCache())
                .resourceChain(true)
                .addResolver(new SpaFallbackResolver());
    }

    private static final class SpaFallbackResolver extends PathResourceResolver {

        private final Resource index = new ClassPathResource("static/index.html");

        @Override
        protected Resource getResource(String resourcePath, Resource location) throws IOException {
            Resource requested = location.createRelative(resourcePath);
            if (requested.exists() && requested.isReadable()) {
                return requested;
            }
            // Unknown API, actuator or asset paths stay 404; everything else is a route of the UI.
            boolean reserved = resourcePath.startsWith("api/") || resourcePath.startsWith("actuator/")
                    || resourcePath.startsWith("assets/");
            return reserved || !index.exists() ? null : index;
        }
    }
}
