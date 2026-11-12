/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.language;

import static org.buggywuggy.common.ApiException.badRequest;
import static org.buggywuggy.common.ApiException.conflict;
import static org.buggywuggy.common.ApiException.notFound;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The languages mails are available in. Each user gets mails in the language of their
 * profile, if that language is enabled, otherwise in the default language.
 */
@Service
@Transactional
public class LanguageService {

    public static final String CODE_PATTERN = "^[a-z]{2,3}(-[a-z0-9]{2,8})?$";
    /** Always available: the built-in templates and texts exist in English. */
    public static final String FALLBACK = "en";

    public record LanguageResponse(String code, String name, boolean enabled, boolean isDefault) {
    }

    public record CreateLanguageRequest(
            @NotBlank @Pattern(regexp = CODE_PATTERN, message = "{validation.language-code.pattern}") String code,
            @NotBlank @Size(max = 100) String name) {
    }

    /** Partial update: {@code null} fields are left unchanged. */
    public record UpdateLanguageRequest(@Size(min = 1, max = 100) String name, Boolean enabled, Boolean isDefault) {
    }

    private final JdbcTemplate jdbc;

    public LanguageService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<LanguageResponse> list(boolean onlyEnabled) {
        return jdbc.query("select code, name, enabled, is_default from language"
                        + (onlyEnabled ? " where enabled" : "") + " order by is_default desc, lower(name), code",
                (rs, n) -> new LanguageResponse(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getBoolean(4)));
    }

    @Transactional(readOnly = true)
    public String defaultCode() {
        return jdbc.queryForList("select code from language where is_default", String.class).stream()
                .findFirst().orElse(FALLBACK);
    }

    /** The enabled language for a user locale such as {@code nl-BE}: exact, then base language, then default. */
    @Transactional(readOnly = true)
    public String resolve(String locale) {
        if (locale != null && !locale.isBlank()) {
            String tag = locale.strip().toLowerCase(Locale.ROOT).replace('_', '-');
            String base = tag.contains("-") ? tag.substring(0, tag.indexOf('-')) : tag;
            List<String> enabled = jdbc.queryForList(
                    "select code from language where enabled and code in (?, ?)", String.class, tag, base);
            if (enabled.contains(tag)) {
                return tag;
            }
            if (enabled.contains(base)) {
                return base;
            }
        }
        return defaultCode();
    }

    @Transactional(readOnly = true)
    public Optional<LanguageResponse> find(String code) {
        return list(false).stream().filter(l -> l.code().equals(code)).findFirst();
    }

    public LanguageResponse require(String code) {
        return find(code).orElseThrow(() -> notFound("language.not-found", code));
    }

    public LanguageResponse create(CreateLanguageRequest request) {
        String code = request.code().strip().toLowerCase(Locale.ROOT);
        if (find(code).isPresent()) {
            throw conflict("language.exists", code);
        }
        jdbc.update("insert into language (code, name, enabled, is_default) values (?, ?, true, false)",
                code, request.name().strip());
        return require(code);
    }

    public LanguageResponse update(String code, UpdateLanguageRequest request) {
        LanguageResponse language = require(code);
        if (request.name() != null) {
            jdbc.update("update language set name = ? where code = ?", request.name().strip(), code);
        }
        if (Boolean.TRUE.equals(request.isDefault()) && !language.isDefault()) {
            jdbc.update("update language set is_default = false where is_default");
            jdbc.update("update language set is_default = true, enabled = true where code = ?", code);
        } else if (Boolean.FALSE.equals(request.isDefault()) && language.isDefault()) {
            throw badRequest("language.default-required");
        }
        if (request.enabled() != null) {
            if (!request.enabled() && require(code).isDefault()) {
                throw badRequest("language.default-cannot-be-disabled");
            }
            jdbc.update("update language set enabled = ? where code = ?", request.enabled(), code);
        }
        return require(code);
    }

    /** Deletes a language and its templates. Users who chose it get mails in the default language. */
    public void delete(String code) {
        if (require(code).isDefault()) {
            throw badRequest("language.default-cannot-be-deleted");
        }
        jdbc.update("delete from language where code = ?", code);
    }
}
