/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The configuration file template: every setting with its default, all commented out. When
 * BuggyWuggy starts without any configuration file, it writes the template to the place it
 * will be read from on the next start. {@code java -jar buggywuggy.jar config-template}
 * prints it instead, for installations where the service cannot write there.
 */
@Component
@ConditionalOnProperty(name = "buggywuggy.config.generate", havingValue = "true", matchIfMissing = true)
public class ConfigTemplate {

    /** The command line argument that prints the template and exits. */
    public static final String COMMAND = "config-template";

    private static final Logger log = LoggerFactory.getLogger(ConfigTemplate.class);

    private final ConfigFiles configFiles;

    public ConfigTemplate(ConfigFiles configFiles) {
        this.configFiles = configFiles;
    }

    public static String text() {
        try (InputStream in = ConfigTemplate.class.getResourceAsStream("/config-template.yml")) {
            if (in == null) {
                throw new IllegalStateException("config-template.yml is missing from the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void writeIfNoConfigFile() {
        if (!configFiles.inUse().isEmpty()) {
            return;
        }
        Path target = configFiles.templateTarget();
        try {
            write(target);
            log.info("No configuration file found. Wrote a template with all settings to {}. "
                    + "It is read at the next start.", target);
        } catch (IOException | RuntimeException e) {
            log.warn("No configuration file found, and a template could not be written to {} ({}). "
                    + "Run 'java -jar buggywuggy.jar {}' to print it.", target, e.toString(), COMMAND);
        }
    }

    /** Writes the template; never overwrites an existing file. Readable by the owner and group only. */
    static void write(Path target) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(target, text(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        try {
            Files.setPosixFilePermissions(target, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r-----"));
        } catch (UnsupportedOperationException e) {
            // not a POSIX file system (Windows): keep the default permissions
        }
    }
}
