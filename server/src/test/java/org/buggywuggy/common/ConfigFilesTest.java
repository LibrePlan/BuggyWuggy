/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

class ConfigFilesTest {

    @Test
    void namesFilesOnDiskButNotTheBuiltInDefaults(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("application.yml");
        Files.writeString(file, "buggywuggy:\n  mail:\n    host: smtp.example.org\n");
        StandardEnvironment environment = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        loader.load("file", new FileSystemResource(file)).forEach(environment.getPropertySources()::addLast);
        loader.load("builtin", new ClassPathResource("application.yml"))
                .forEach(environment.getPropertySources()::addLast);

        assertThat(new ConfigFiles(environment).inUse()).containsExactly(file.toAbsolutePath().toString());
    }

    @Test
    void isEmptyWithoutFiles() {
        assertThat(new ConfigFiles(new StandardEnvironment()).inUse()).isEmpty();
    }
}
