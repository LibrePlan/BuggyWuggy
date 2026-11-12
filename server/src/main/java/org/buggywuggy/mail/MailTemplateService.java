/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.mail;

import static org.buggywuggy.common.ApiException.notFound;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.buggywuggy.common.ApiException;
import org.buggywuggy.common.ApiNullable;
import org.buggywuggy.language.LanguageService;
import org.buggywuggy.mail.TemplateEngine.Keyword;
import org.buggywuggy.mail.TemplateEngine.Problem;
import org.buggywuggy.user.UserDtos.UserRef;
import org.springframework.context.MessageSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mail templates per language. A template changed by an administrator is stored in the
 * database; otherwise the built-in default for that language is used. A language without a
 * built-in default falls back to the default language, and finally to English.
 */
@Service
@Transactional
public class MailTemplateService {

    /** Subject and HTML body of a template. */
    public record Template(String subject, String body) {
    }

    /** A rendered mail. */
    public record RenderedMail(String subject, String html, String text) {
    }

    public record TemplateRequest(@NotNull @Size(max = 500) String subject, @NotNull @Size(max = 100_000) String body) {
    }

    public record KeywordInfo(String name, String description, boolean block) {
    }

    /** Texts of the keyword help next to the editor, in the viewer's language. */
    public record KeywordHelp(String title, String intro, String insert, String block, String html) {
    }

    public record TemplateSummary(String key, String name, String description, List<TemplateLanguageState> languages) {
    }

    public record TemplateLanguageState(String language, boolean customized, @ApiNullable Instant updatedAt) {
    }

    public record TemplateResponse(String key, String name, String description, String language, String subject,
            String body, boolean customized, @ApiNullable Instant updatedAt, @ApiNullable UserRef updatedBy,
            List<KeywordInfo> keywords, KeywordHelp help) {
    }

    public record ProblemInfo(String field, int line, String message) {
    }

    public record PreviewResponse(String subject, String html, String text, List<ProblemInfo> problems) {
    }

    private final JdbcTemplate jdbc;
    private final LanguageService languages;
    private final MessageSource messages;
    private final Clock clock;
    private final Map<String, Optional<Template>> builtIn = new ConcurrentHashMap<>();

    public MailTemplateService(JdbcTemplate jdbc, LanguageService languages, MessageSource messages, Clock clock) {
        this.jdbc = jdbc;
        this.languages = languages;
        this.messages = messages;
        this.clock = clock;
    }

    // --- use -------------------------------------------------------------------------

    /** The template to use for mails in {@code language}, following the fallback chain. */
    @Transactional(readOnly = true)
    public Template effective(MailTemplateKey key, String language) {
        return stored(key, language)
                .or(() -> builtIn(key, language))
                .or(() -> stored(key, languages.defaultCode()))
                .or(() -> builtIn(key, languages.defaultCode()))
                .or(() -> builtIn(key, LanguageService.FALLBACK))
                .orElseThrow(() -> new IllegalStateException("No built-in template " + key.key()));
    }

    /**
     * Renders a template. {@code values} gives plain-text values of inline keywords,
     * {@code blocks} the HTML of block keywords.
     */
    public static RenderedMail render(MailTemplateKey key, Template template, Function<String, String> values,
            Function<String, String> blocks) {
        Map<String, Keyword> keywords = key.keywords();
        String subject = TemplateEngine.renderSubject(template.subject(), values);
        String html = HtmlMail.document(TemplateEngine.renderHtml(template.body(), keywords, values, blocks));
        return new RenderedMail(subject, html, HtmlMail.text(html));
    }

    // --- administration --------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<TemplateSummary> list(Locale viewer) {
        Map<String, Instant> customized = new ConcurrentHashMap<>();
        jdbc.query("select template_key, language_code, updated_at from mail_template",
                rs -> {
                    customized.put(rs.getString(1) + "/" + rs.getString(2), rs.getTimestamp(3).toInstant());
                });
        List<LanguageService.LanguageResponse> enabled = languages.list(true);
        List<TemplateSummary> result = new ArrayList<>();
        for (MailTemplateKey key : MailTemplateKey.values()) {
            List<TemplateLanguageState> states = enabled.stream().map(l -> {
                Instant at = customized.get(key.key() + "/" + l.code());
                return new TemplateLanguageState(l.code(), at != null, at);
            }).toList();
            result.add(new TemplateSummary(key.key(), name(key, viewer), description(key, viewer), states));
        }
        return result;
    }

    /** The template as an administrator edits it; texts about it in the viewer's language. */
    @Transactional(readOnly = true)
    public TemplateResponse get(String keyName, String language, Locale viewer) {
        MailTemplateKey key = requireKey(keyName);
        languages.require(language);
        record Stored(Template template, Instant at, UserRef by) {
        }
        Optional<Stored> stored = jdbc.query("""
                select t.subject, t.body, t.updated_at, u.id, u.username, u.display_name
                from mail_template t left join user_account u on u.id = t.updated_by
                where t.template_key = ? and t.language_code = ?""", (rs, n) -> {
            long userId = rs.getLong(4);
            UserRef by = rs.wasNull() ? null : new UserRef(userId, rs.getString(5), rs.getString(6));
            return new Stored(new Template(rs.getString(1), rs.getString(2)), rs.getTimestamp(3).toInstant(), by);
        }, key.key(), language).stream().findFirst();
        Template template = stored.map(Stored::template).orElseGet(() -> defaultFor(key, language));
        return new TemplateResponse(key.key(), name(key, viewer), description(key, viewer), language,
                template.subject(), template.body(), stored.isPresent(), stored.map(Stored::at).orElse(null),
                stored.map(Stored::by).orElse(null), keywords(key, viewer), help(viewer));
    }

    public TemplateResponse save(String keyName, String language, TemplateRequest request, long userId,
            Locale viewer) {
        MailTemplateKey key = requireKey(keyName);
        languages.require(language);
        List<ProblemInfo> problems = validate(key, request, viewer);
        if (!problems.isEmpty()) {
            throw new TemplateInvalidException(problems);
        }
        jdbc.update("""
                insert into mail_template (template_key, language_code, subject, body, updated_at, updated_by)
                values (?, ?, ?, ?, ?, ?)
                on conflict (template_key, language_code)
                do update set subject = excluded.subject, body = excluded.body,
                              updated_at = excluded.updated_at, updated_by = excluded.updated_by""",
                key.key(), language, request.subject().strip(), request.body(), Timestamp.from(clock.instant()),
                userId);
        return get(keyName, language, viewer);
    }

    /** Goes back to the built-in template. */
    public TemplateResponse reset(String keyName, String language, Locale viewer) {
        MailTemplateKey key = requireKey(keyName);
        languages.require(language);
        jdbc.update("delete from mail_template where template_key = ? and language_code = ?", key.key(), language);
        return get(keyName, language, viewer);
    }

    /** Renders unsaved template text with sample data. */
    public PreviewResponse preview(String keyName, String language, TemplateRequest request, Locale viewer) {
        MailTemplateKey key = requireKey(keyName);
        languages.require(language);
        List<ProblemInfo> problems = validate(key, request, viewer);
        RenderedMail mail = SampleData.render(key, new Template(request.subject(), request.body()),
                Locale.forLanguageTag(language), messages);
        return new PreviewResponse(mail.subject(), mail.html(), mail.text(), problems);
    }

    public List<ProblemInfo> validate(MailTemplateKey key, TemplateRequest request, Locale viewer) {
        Map<String, Keyword> keywords = key.keywords();
        List<ProblemInfo> result = new ArrayList<>();
        if (request.subject().isBlank()) {
            result.add(new ProblemInfo("subject", 1, message("mail-template.subject-required", viewer)));
        }
        for (Problem p : TemplateEngine.validate(request.subject(), keywords, true)) {
            result.add(new ProblemInfo("subject", p.line(), message(p.code(), viewer, p.keyword())));
        }
        for (Problem p : TemplateEngine.validate(request.body(), keywords, false)) {
            result.add(new ProblemInfo("body", p.line(),
                    message("mail-template.line", viewer, p.line(), message(p.code(), viewer, p.keyword()))));
        }
        return result;
    }

    public KeywordHelp help(Locale viewer) {
        return new KeywordHelp(message("mail.help.title", viewer), message("mail.help.intro", viewer),
                message("mail.help.insert", viewer), message("mail.help.block", viewer),
                message("mail.help.html", viewer));
    }

    public List<KeywordInfo> keywords(MailTemplateKey key, Locale viewer) {
        return key.keywords().values().stream()
                .map(k -> new KeywordInfo(k.name(), message("mail.keyword." + k.name(), viewer), k.block()))
                .toList();
    }

    /** The built-in template of a language, falling back like {@link #effective} without stored rows. */
    public Template defaultFor(MailTemplateKey key, String language) {
        return builtIn(key, language)
                .or(() -> builtIn(key, languages.defaultCode()))
                .or(() -> builtIn(key, LanguageService.FALLBACK))
                .orElseThrow();
    }

    public static MailTemplateKey requireKey(String key) {
        return MailTemplateKey.of(key).orElseThrow(() -> notFound("mail-template.not-found", key));
    }

    // --- helpers ---------------------------------------------------------------------

    private Optional<Template> stored(MailTemplateKey key, String language) {
        return jdbc.query("select subject, body from mail_template where template_key = ? and language_code = ?",
                (rs, n) -> new Template(rs.getString(1), rs.getString(2)), key.key(), language).stream().findFirst();
    }

    /**
     * Built-in templates are files {@code mail-templates/<language>/<key>.html} whose first
     * line is {@code Subject: …}, followed by an empty line and the HTML body.
     */
    private Optional<Template> builtIn(MailTemplateKey key, String language) {
        return builtIn.computeIfAbsent(language + "/" + key.key(), path -> {
            ClassPathResource resource = new ClassPathResource("mail-templates/" + path + ".html");
            if (!resource.exists()) {
                return Optional.empty();
            }
            try (InputStream in = resource.getInputStream()) {
                String content = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
                int end = content.indexOf('\n');
                String first = content.substring(0, end);
                if (!first.startsWith("Subject:")) {
                    throw new IllegalStateException(path + ": first line must be 'Subject: …'");
                }
                return Optional.of(new Template(first.substring("Subject:".length()).strip(),
                        content.substring(end + 1).strip() + "\n"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private String name(MailTemplateKey key, Locale viewer) {
        return message("mail.template." + key.key() + ".name", viewer);
    }

    private String description(MailTemplateKey key, Locale viewer) {
        return message("mail.template." + key.key() + ".description", viewer);
    }

    private String message(String code, Locale locale, Object... args) {
        return messages.getMessage(code, args, code, locale);
    }

    /** A template with problems; answered as 400 with the problems as field errors. */
    public static class TemplateInvalidException extends ApiException {

        private final transient List<ProblemInfo> problems;

        TemplateInvalidException(List<ProblemInfo> problems) {
            super(HttpStatus.BAD_REQUEST, "mail-template.invalid");
            this.problems = List.copyOf(problems);
        }

        public List<ProblemInfo> problems() {
            return problems;
        }

        @Override
        public List<Map<String, String>> fieldErrors() {
            return problems.stream().map(p -> Map.of("field", p.field(), "message", p.message())).toList();
        }
    }
}
