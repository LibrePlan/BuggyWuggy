/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.buggywuggy.mail.MailProperties;
import org.buggywuggy.taskwarrior.UrgencyProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.yaml.snakeyaml.Yaml;

class ConfigTemplateTest {

    /** The template with every setting switched on, as an administrator would. */
    private static Map<String, Object> uncommented() {
        String yaml = ConfigTemplate.text().lines()
                .filter(line -> !line.startsWith("##"))
                .map(line -> line.startsWith("#") ? line.substring(1) : line)
                .collect(Collectors.joining("\n"));
        return new Yaml().load(yaml);
    }

    private static Set<String> keys(String prefix, Map<?, ?> map) {
        Set<String> keys = new TreeSet<>();
        map.forEach((key, value) -> {
            String name = prefix.isEmpty() ? key.toString() : prefix + "." + key;
            if (value instanceof Map<?, ?> child) {
                keys.addAll(keys(name, child));
            } else {
                keys.add(name);
            }
        });
        return keys;
    }

    private static Map<String, Object> flat(String prefix, Map<?, ?> map) {
        Map<String, Object> flat = new TreeMap<>();
        map.forEach((key, value) -> {
            String name = prefix.isEmpty() ? key.toString() : prefix + "." + key;
            if (value instanceof Map<?, ?> child) {
                flat.putAll(flat(name, child));
            } else {
                flat.put(name, value);
            }
        });
        return flat;
    }

    @Test
    void everySettingIsCommentedOut() {
        assertThat(ConfigTemplate.text().lines().filter(line -> !line.isBlank()))
                .allMatch(line -> line.startsWith("#"));
    }

    @Test
    void containsEverySettingAnAdministratorCanChange() throws Exception {
        Set<String> template = keys("", uncommented());
        Map<String, Object> builtIn;
        try (InputStream in = getClass().getResourceAsStream("/application.yml")) {
            builtIn = flat("", new Yaml().load(in));
        }
        Set<String> changeable = builtIn.entrySet().stream()
                .filter(e -> e.getKey().startsWith("buggywuggy.") || String.valueOf(e.getValue()).contains("${BUGGYWUGGY_"))
                .map(Map.Entry::getKey)
                .collect(Collectors.toCollection(TreeSet::new));
        Arrays.stream(MailProperties.class.getRecordComponents())
                .map(c -> "buggywuggy.mail." + c.getName().replaceAll("([A-Z])", "-$1").toLowerCase())
                .forEach(changeable::add);
        // The maps (priority, tags, projects) are listed with their default entries.
        Arrays.stream(UrgencyProperties.class.getRecordComponents())
                .filter(c -> c.getType() != Map.class)
                .map(c -> "buggywuggy.taskwarrior.urgency." + c.getName().replaceAll("([A-Z])", "-$1").toLowerCase())
                .forEach(changeable::add);
        changeable.add("buggywuggy.taskwarrior.urgency.priority.Highest");
        changeable.add("buggywuggy.taskwarrior.urgency.tags.next");

        assertThat(template).containsAll(changeable);
    }

    @Test
    void isWrittenWhereSpringReadsItAndNeverOverwritten(@TempDir Path dir) throws Exception {
        ConfigFiles files = new ConfigFiles(new StandardEnvironment(), dir);
        assertThat(files.inUse()).isEmpty();
        Path target = files.templateTarget();
        assertThat(target).isEqualTo(dir.resolve("config/application.yml"));

        ConfigTemplate.write(target);

        assertThat(Files.readString(target)).isEqualTo(ConfigTemplate.text());
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target))).isEqualTo("rw-r-----");
        // only comments, so Spring has no property source for it, but it does count as in use
        assertThat(files.inUse()).containsExactly(target.toString());
        assertThatThrownBy(() -> ConfigTemplate.write(target)).isInstanceOf(FileAlreadyExistsException.class);
    }

    @Test
    void followsAnOptionalConfiguredLocation(@TempDir Path dir) {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("args", Map.of(
                "spring.config.additional-location", "optional:file:" + dir.resolve("etc/application.yml"))));
        assertThat(new ConfigFiles(environment, dir).templateTarget()).isEqualTo(dir.resolve("etc/application.yml"));
    }
}
