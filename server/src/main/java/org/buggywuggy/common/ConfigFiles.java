/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.common;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
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
 * The configuration files on disk that BuggyWuggy reads, so the administration screens can
 * name them and a template can be written when there is none. The built-in defaults inside
 * the jar do not count.
 */
@Component
public class ConfigFiles {

    private static final List<String> NAMES = List.of("application.yml", "application.yaml",
            "application.properties");

    private final ConfigurableEnvironment environment;
    private final Path workingDirectory;

    @Autowired
    public ConfigFiles(ConfigurableEnvironment environment) {
        this(environment, Path.of(""));
    }

    ConfigFiles(ConfigurableEnvironment environment, Path workingDirectory) {
        this.environment = environment;
        this.workingDirectory = workingDirectory.toAbsolutePath().normalize();
    }

    /**
     * Absolute paths, highest precedence first; empty when no file outside the jar is used.
     * Includes files that exist but contain no settings (e.g. a template with everything
     * commented out), which Spring does not report as a property source.
     */
    public List<String> inUse() {
        Set<String> paths = new LinkedHashSet<>();
        for (PropertySource<?> source : environment.getPropertySources()) {
            Path path = pathOf(source);
            if (path != null) {
                paths.add(path.toString());
            }
        }
        for (Location location : locations()) {
            for (Path candidate : location.candidates()) {
                if (Files.isRegularFile(candidate)) {
                    paths.add(candidate.toString());
                }
            }
        }
        return List.copyOf(paths);
    }

    /**
     * Where a new configuration file is read from on the next start: the first file location
     * given with spring.config.(additional-)location, else the first directory given there,
     * else config/application.yml in the working directory.
     */
    public Path templateTarget() {
        List<Location> locations = locations();
        for (Location location : locations) {
            if (location.configured() && !location.directory()) {
                return location.path();
            }
        }
        for (Location location : locations) {
            if (location.configured()) {
                return location.path().resolve("application.yml");
            }
        }
        return workingDirectory.resolve("config").resolve("application.yml");
    }

    // --- helpers -------------------------------------------------------------------------

    private record Location(Path path, boolean directory, boolean configured) {

        List<Path> candidates() {
            return directory ? NAMES.stream().map(path::resolve).toList() : List.of(path);
        }
    }

    /** File system locations Spring Boot reads configuration from, in the order it lists them. */
    private List<Location> locations() {
        List<Location> result = new ArrayList<>();
        String replaced = environment.getProperty("spring.config.location");
        result.addAll(parse(environment.getProperty("spring.config.additional-location")));
        result.addAll(parse(replaced));
        if (replaced == null || replaced.isBlank()) {
            result.add(new Location(workingDirectory.resolve("config"), true, false));
            result.add(new Location(workingDirectory, true, false));
        }
        return result;
    }

    private List<Location> parse(String value) {
        List<Location> result = new ArrayList<>();
        if (value == null) {
            return result;
        }
        for (String part : value.split("[,;]")) {
            String location = part.trim();
            if (location.startsWith("optional:")) {
                location = location.substring("optional:".length());
            }
            if (location.startsWith("file:")) {
                location = location.substring("file:".length());
            } else if (location.isEmpty() || location.contains(":") && !looksLikeWindowsPath(location)) {
                continue; // classpath: and other non-file locations
            }
            boolean directory = location.endsWith("/") || location.endsWith("\\");
            String lower = location.toLowerCase(Locale.ROOT);
            if (!directory && !(lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".properties"))) {
                continue; // e.g. a pattern or a profile-specific location we do not try to follow
            }
            result.add(new Location(workingDirectory.resolve(location).normalize(), directory, true));
        }
        return result;
    }

    private static boolean looksLikeWindowsPath(String location) {
        return location.length() > 2 && location.charAt(1) == ':' && Character.isLetter(location.charAt(0));
    }

    @SuppressWarnings("unchecked")
    private Path pathOf(PropertySource<?> source) {
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
            return resource.getFile().toPath().toAbsolutePath().normalize();
        } catch (IOException e) {
            return null;
        }
    }
}
