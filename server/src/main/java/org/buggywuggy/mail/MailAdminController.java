/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.mail;

import static org.buggywuggy.common.ApiException.badRequest;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Locale;
import org.buggywuggy.common.ApiException;
import org.buggywuggy.common.ApiNullable;
import org.buggywuggy.language.LanguageService;
import org.buggywuggy.mail.MailOutbox.FailedMail;
import org.buggywuggy.mail.MailOutbox.OutboxStatus;
import org.buggywuggy.mail.MailTemplateService.PreviewResponse;
import org.buggywuggy.mail.MailTemplateService.RenderedMail;
import org.buggywuggy.mail.MailTemplateService.Template;
import org.buggywuggy.mail.MailTemplateService.TemplateRequest;
import org.buggywuggy.mail.MailTemplateService.TemplateResponse;
import org.buggywuggy.mail.MailTemplateService.TemplateSummary;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mail administration: status, test mails, failed mails and templates. Requires the
 * ADMINISTER global permission (see SecurityConfiguration). Texts about templates and
 * keywords are in the administrator's own language.
 */
@RestController
@RequestMapping("/api/v1/admin/mail")
public class MailAdminController {

    public record MailStatus(boolean configured, @ApiNullable String host, int port, String security, String from,
            String baseUrl, OutboxStatus outbox) {
    }

    private final MailTransport transport;
    private final MailOutbox outbox;
    private final MailTemplateService templates;
    private final LanguageService languages;
    private final MessageSource messages;
    private final JdbcTemplate jdbc;
    private final String baseUrl;

    public MailAdminController(MailTransport transport, MailOutbox outbox, MailTemplateService templates,
            LanguageService languages, MessageSource messages, JdbcTemplate jdbc,
            @Value("${buggywuggy.base-url}") String baseUrl) {
        this.transport = transport;
        this.outbox = outbox;
        this.templates = templates;
        this.languages = languages;
        this.messages = messages;
        this.jdbc = jdbc;
        this.baseUrl = baseUrl;
    }

    @GetMapping("/status")
    public MailStatus status() {
        MailProperties p = transport.properties();
        return new MailStatus(transport.configured(), p.host(), p.port(), p.security().name().toLowerCase(Locale.ROOT),
                p.from(), baseUrl, outbox.status());
    }

    /** Sends the test mail to the administrator, right away, so a configuration error shows immediately. */
    @PostMapping("/test")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sendTest(@AuthenticationPrincipal BuggyWuggyPrincipal user) {
        Me me = me(user);
        String language = languages.resolve(me.locale());
        sendNow(me, MailTemplateKey.TEST_MAIL, templates.effective(MailTemplateKey.TEST_MAIL, language), language);
    }

    @GetMapping("/failed")
    public List<FailedMail> failed() {
        return outbox.failed();
    }

    @PostMapping("/failed/{id}/retry")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void retry(@PathVariable long id) {
        outbox.retry(id);
    }

    @DeleteMapping("/failed/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void discard(@PathVariable long id) {
        outbox.discard(id);
    }

    // --- templates -----------------------------------------------------------------------

    @GetMapping("/templates")
    public List<TemplateSummary> templates(@AuthenticationPrincipal BuggyWuggyPrincipal user) {
        return templates.list(viewerLocale(user));
    }

    @GetMapping("/templates/{key}/{language}")
    public TemplateResponse template(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String key,
            @PathVariable String language) {
        return templates.get(key, language, viewerLocale(user));
    }

    @PutMapping("/templates/{key}/{language}")
    public TemplateResponse save(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String key,
            @PathVariable String language, @Valid @RequestBody TemplateRequest request) {
        return templates.save(key, language, request, user.id(), viewerLocale(user));
    }

    /** Goes back to the built-in template. */
    @DeleteMapping("/templates/{key}/{language}")
    public TemplateResponse reset(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String key,
            @PathVariable String language) {
        return templates.reset(key, language, viewerLocale(user));
    }

    @PostMapping("/templates/{key}/{language}/preview")
    public PreviewResponse preview(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String key,
            @PathVariable String language, @Valid @RequestBody TemplateRequest request) {
        return templates.preview(key, language, request, viewerLocale(user));
    }

    /** Sends the (unsaved) template with sample data to the administrator. */
    @PostMapping("/templates/{key}/{language}/test")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void testTemplate(@AuthenticationPrincipal BuggyWuggyPrincipal user, @PathVariable String key,
            @PathVariable String language, @Valid @RequestBody TemplateRequest request) {
        MailTemplateKey templateKey = MailTemplateService.requireKey(key);
        languages.require(language);
        Locale viewer = viewerLocale(user);
        var problems = templates.validate(templateKey, request, viewer);
        if (!problems.isEmpty()) {
            throw new MailTemplateService.TemplateInvalidException(problems);
        }
        sendNow(me(user), templateKey, new Template(request.subject(), request.body()), language);
    }

    // --- helpers -------------------------------------------------------------------------

    private record Me(String name, String email, String locale) {
    }

    private Me me(BuggyWuggyPrincipal user) {
        return jdbc.queryForObject("select display_name, email, locale from user_account where id = ?",
                (rs, n) -> new Me(rs.getString(1), rs.getString(2), rs.getString(3)), user.id());
    }

    private void sendNow(Me me, MailTemplateKey key, Template template, String language) {
        if (!transport.configured()) {
            throw new ApiException(HttpStatus.CONFLICT, "mail.not-configured");
        }
        if (me.email() == null) {
            throw badRequest("mail.no-address");
        }
        RenderedMail mail = SampleData.render(key, template, Locale.forLanguageTag(language), messages);
        try {
            transport.send(me.email(), mail.subject(), mail.html(), mail.text());
        } catch (MailException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "mail.send-failed", String.valueOf(e.getMessage()));
        }
    }

    /** The administrator's profile language if set, otherwise the browser's. */
    private Locale viewerLocale(BuggyWuggyPrincipal user) {
        String locale = jdbc.queryForObject("select locale from user_account where id = ?", String.class, user.id());
        return locale == null || locale.isBlank() ? LocaleContextHolder.getLocale() : Locale.forLanguageTag(locale);
    }
}
