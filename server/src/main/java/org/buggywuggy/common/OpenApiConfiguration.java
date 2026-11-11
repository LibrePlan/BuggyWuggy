/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.common;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document at {@code /api/openapi}.
 *
 * <p>Response objects always contain all their fields; fields that can be {@code null} are
 * annotated with {@link ApiNullable}. The document reflects that: every property of a response
 * schema is {@code required}, and nullable ones allow {@code null}. Input schemas (names ending
 * in {@code Request}, and {@code IssueQuery}) are left alone: their optional fields may simply be omitted.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    @Bean
    OpenAPI openApi() {
        return new OpenAPI().info(new Info()
                .title("BuggyWuggy API")
                .version("v1")
                .license(new License().name("AGPL-3.0-or-later").url("https://www.gnu.org/licenses/agpl-3.0.html")));
    }

    /**
     * Schema properties ({@code Schema.property}) annotated with {@link ApiNullable}. Collected
     * while the schemas are built and applied afterwards: during building, the call for a list
     * property cannot be told apart from the call for its items.
     */
    private final Set<String> nullableProperties = ConcurrentHashMap.newKeySet();

    @Bean
    PropertyCustomizer nullablePropertyCustomizer() {
        return (property, type) -> {
            Annotation[] annotations = type.getCtxAnnotations();
            if (annotations != null && type.getParent() != null && type.getPropertyName() != null) {
                for (Annotation annotation : annotations) {
                    if (annotation instanceof ApiNullable) {
                        nullableProperties.add(type.getParent().getName() + "." + type.getPropertyName());
                    }
                }
            }
            return property;
        };
    }

    @Bean
    @SuppressWarnings({"rawtypes", "unchecked"})
    OpenApiCustomizer requiredResponseFieldsCustomizer() {
        return openApi -> {
            if (openApi.getComponents() == null || openApi.getComponents().getSchemas() == null) {
                return;
            }
            for (Map.Entry<String, Schema> entry : openApi.getComponents().getSchemas().entrySet()) {
                Schema<?> schema = entry.getValue();
                if (schema.getProperties() == null) {
                    continue;
                }
                Map<String, Schema> properties = (Map) schema.getProperties();
                for (Map.Entry<String, Schema> property : properties.entrySet()) {
                    if (nullableProperties.contains(entry.getKey() + "." + property.getKey())) {
                        property.setValue(nullable(property.getValue()));
                    }
                }
                if (!isInput(entry.getKey())) {
                    schema.setRequired(new ArrayList<>(properties.keySet()));
                }
            }
        };
    }

    /** Input schemas: requests, and the issue query (also used inside saved filters, sparsely). */
    private static boolean isInput(String schemaName) {
        return schemaName.endsWith("Request") || schemaName.equals("IssueQuery");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Schema nullable(Schema property) {
        if (property.get$ref() != null) {
            // A $ref cannot carry other keywords reliably; wrap it.
            Schema wrapper = new Schema();
            wrapper.setOneOf(List.of(new Schema().$ref(property.get$ref()), new Schema().types(Set.of("null"))));
            wrapper.setDescription(property.getDescription());
            return wrapper;
        }
        if (property.getOneOf() != null || property.getTypes() != null && property.getTypes().contains("null")) {
            return property;
        }
        if (property.getTypes() != null) {
            property.addType("null");
        } else if (property.getType() != null) {
            property.types(new java.util.LinkedHashSet<>(List.of(property.getType(), "null")));
        }
        property.setNullable(true);
        return property;
    }
}
