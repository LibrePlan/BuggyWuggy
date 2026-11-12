/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.language;

import jakarta.validation.Valid;
import java.util.List;
import org.buggywuggy.language.LanguageService.CreateLanguageRequest;
import org.buggywuggy.language.LanguageService.LanguageResponse;
import org.buggywuggy.language.LanguageService.UpdateLanguageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Enabled languages for everyone (profile); management under /admin (ADMINISTER). */
@RestController
public class LanguageController {

    private final LanguageService languages;

    public LanguageController(LanguageService languages) {
        this.languages = languages;
    }

    @GetMapping("/api/v1/languages")
    public List<LanguageResponse> enabled() {
        return languages.list(true);
    }

    @GetMapping("/api/v1/admin/languages")
    public List<LanguageResponse> all() {
        return languages.list(false);
    }

    @PostMapping("/api/v1/admin/languages")
    @ResponseStatus(HttpStatus.CREATED)
    public LanguageResponse create(@Valid @RequestBody CreateLanguageRequest request) {
        return languages.create(request);
    }

    @PatchMapping("/api/v1/admin/languages/{code}")
    public LanguageResponse update(@PathVariable String code, @Valid @RequestBody UpdateLanguageRequest request) {
        return languages.update(code, request);
    }

    @DeleteMapping("/api/v1/admin/languages/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String code) {
        languages.delete(code);
    }
}
