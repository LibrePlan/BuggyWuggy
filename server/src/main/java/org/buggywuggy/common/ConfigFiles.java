/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.common;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.origin.Origin;
import org.springframework.boot.origin.OriginLookup;
import org.springframework.boot.origin.TextResourceOrigin;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * The configuration files on disk that BuggyWuggy actually loaded, so the administration
 * screens can name them. The built-in defaults inside the jar are not included.
 */
@Component
public class ConfigFiles {

    private final ConfigurableEnvironment environment;

    public ConfigFiles(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    /** Absolute paths, highest precedence first; empty when no file outside the jar is used. */
    public List<String> inUse() {
        Set<String> paths = new LinkedHashSet<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            String path = pathOf(source);
            if (path != null) {
                paths.add(path);
            }
        }
        return List.copyOf(paths);
    }

    @SuppressWarnings("unchecked")
    private static String pathOf(PropertySource<?> source) {
        if (!(source instanceof OriginLookup<?>) || !(source instanceof EnumerablePropertySource<?> enumerable)) {
            return null;
        }
        String[] names = enumerable.getPropertyNames();
        if (names.length == 0) {
            return null;
        }
        Origin origin = ((OriginLookup<String>) source).getOrigin(names[0]);
        while (origin != null && !(origin instanceof TextResourceOrigin)) {
            origin = origin.getParent();
        }
        if (origin == null) {
            return null;
        }
        Resource resource = ((TextResourceOrigin) origin).getResource();
        if (resource == null || resource instanceof ClassPathResource || !resource.isFile()) {
            return null;
        }
        try {
            return resource.getFile().getAbsoluteFile().toPath().normalize().toString();
        } catch (IOException e) {
            return null;
        }
    }
}
