/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.license;

import static org.buggywuggy.common.ApiException.badRequest;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.buggywuggy.common.ApiNullable;
import org.buggywuggy.common.BuildVersionController;
import org.buggywuggy.common.BuildVersionController.BuildVersionResponse;
import org.buggywuggy.license.LicensePricing.Quote;
import org.buggywuggy.mail.HtmlMail;
import org.buggywuggy.mail.MailAttachment;
import org.buggywuggy.mail.MailOutbox;
import org.buggywuggy.mail.MailTransport;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Annual donations that support the project (called licenses in the code and the database). An
 * administrator asks for a quotation: the request is mailed to {@link #SALES} with a new
 * challenge. The signed answer (a donation code, see {@link LicenseCode}) entered by the
 * administrator is valid until the date in the code; while one is valid, the login page shows
 * the installation as an active supporter. A month before it expires, {@link #SALES} is told so,
 * to discuss a renewal.
 */
@Service
@Transactional
public class LicenseService {

    /** Where quotation requests and expiry reminders go. */
    public static final String SALES = "sales@buggywuggy.org";

    /**
     * Attached to every mail to {@link #SALES}: the request in a form a program can read
     * (format {@value #FORMAT}, field "kind" is "quotation" or "renewal").
     */
    public static final String ATTACHMENT = "buggywuggy-donation.json";
    public static final String FORMAT = "buggywuggy-donation/1";

    private static final Logger log = LoggerFactory.getLogger(LicenseService.class);

    /** Whether this installation supports the project, as the login page shows it. */
    public enum SupportStatus {
        /** No donation code was ever entered. */
        NONE,
        /** A donation code is valid today. */
        ACTIVE,
        /** Donation codes were entered, but all have expired. */
        EXPIRED
    }

    public record LicenseRequestSummary(String challenge, Instant createdAt, String companyName, String currency,
            BigDecimal yearlyTotal, int users) {
    }

    /**
     * {@code validUntil}: the last day of the current license (null without one).
     * {@code userCount}: active accounts, the default number of users of a quotation.
     */
    public record LicenseStatus(boolean licensed, @ApiNullable LocalDate validUntil, int userCount,
            boolean mailConfigured, String salesAddress, List<LicenseRequestSummary> requests) {
    }

    public record QuotationRequest(
            @NotBlank @Size(max = 2) String country,
            @NotNull @Min(1) @Max(1_000_000) Integer users,
            @NotBlank @Size(max = 200) String companyName,
            @NotBlank @Size(max = 200) String contactName,
            @NotBlank @Email @Size(max = 254) String contactEmail,
            @Size(max = 50) String contactPhone) {
    }

    /**
     * The request as it was mailed ({@code subject}, {@code text}), so it can also be sent by
     * hand when BuggyWuggy cannot send mail.
     */
    public record QuotationResponse(String challenge, Quote quote, boolean mailConfigured, String to, String subject,
            String text) {
    }

    public record ActivateRequest(@NotBlank @Size(max = 200) String code) {
    }

    /**
     * The donation that is valid today, as proof in a feedback mail: the challenge and the
     * signed donation code (verifiable with the public key), and who donated.
     */
    public record Donor(String challenge, String code, LocalDate validUntil, String companyName) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final LicensePricing pricing;
    private final LicenseCode codes;
    private final MailOutbox outbox;
    private final MailTransport transport;
    private final Clock clock;
    private final String baseUrl;
    private final BuildVersionController version;
    private final JsonMapper json;

    public LicenseService(NamedParameterJdbcTemplate jdbc, LicensePricing pricing, LicenseCode codes,
            MailOutbox outbox, MailTransport transport, Clock clock, @Value("${buggywuggy.base-url}") String baseUrl,
            BuildVersionController version, JsonMapper json) {
        this.jdbc = jdbc;
        this.pricing = pricing;
        this.codes = codes;
        this.outbox = outbox;
        this.transport = transport;
        this.clock = clock;
        this.baseUrl = baseUrl;
        this.version = version;
        this.json = json;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneId.systemDefault());
    }

    /** The last day of the license that is valid today, if any. */
    @Transactional(readOnly = true)
    public Optional<LocalDate> validUntil() {
        return Optional.ofNullable(jdbc.queryForObject(
                "select max(valid_until) from license where valid_until >= :today",
                Map.of("today", today()), LocalDate.class));
    }

    /**
     * The donation that is valid today, with its code; empty without one, or when its code was
     * entered before codes were kept (entering it again fixes that).
     */
    @Transactional(readOnly = true)
    public Optional<Donor> donor() {
        return jdbc.query("""
                select l.challenge, l.code, l.valid_until, r.company_name
                from license l join license_request r on r.challenge = l.challenge
                where l.valid_until >= :today and l.code is not null
                order by l.valid_until desc, l.id desc
                limit 1""", Map.of("today", today()), (rs, n) -> new Donor(rs.getString("challenge"),
                rs.getString("code"), rs.getObject("valid_until", LocalDate.class), rs.getString("company_name")))
                .stream().findFirst();
    }

    @Transactional(readOnly = true)
    public SupportStatus supportStatus() {
        if (validUntil().isPresent()) {
            return SupportStatus.ACTIVE;
        }
        boolean ever = jdbc.queryForObject("select exists (select 1 from license)", Map.of(), Boolean.class);
        return ever ? SupportStatus.EXPIRED : SupportStatus.NONE;
    }

    @Transactional(readOnly = true)
    public int userCount() {
        return jdbc.queryForObject("select count(*) from user_account where active and not placeholder", Map.of(),
                Integer.class);
    }

    @Transactional(readOnly = true)
    public LicenseStatus status() {
        Optional<LocalDate> until = validUntil();
        List<LicenseRequestSummary> requests = jdbc.query("""
                select challenge, created_at, company_name, currency, yearly_total, users
                from license_request r
                where not exists (select 1 from license l where l.challenge = r.challenge)
                order by created_at desc
                limit 5""", Map.of(), (rs, n) -> new LicenseRequestSummary(rs.getString("challenge"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("company_name"), rs.getString("currency"),
                rs.getBigDecimal("yearly_total"), rs.getInt("users")));
        return new LicenseStatus(until.isPresent(), until.orElse(null), userCount(), transport.configured(), SALES,
                requests);
    }

    @Transactional(readOnly = true)
    public Quote quote(String country, Integer users) {
        int count = users == null ? Math.max(userCount(), 1) : users;
        if (count < 1 || count > 1_000_000) {
            throw badRequest("license.users-invalid");
        }
        return pricing.quote(country, count);
    }

    /** Stores a new challenge and mails the request to {@link #SALES}. */
    public QuotationResponse requestQuotation(BuggyWuggyPrincipal user, QuotationRequest request) {
        Quote quote = pricing.quote(request.country(), request.users());
        String challenge = codes.newChallenge();
        Instant now = clock.instant();
        String phone = request.contactPhone() == null || request.contactPhone().isBlank() ? null
                : request.contactPhone().strip();
        jdbc.update("""
                insert into license_request (challenge, created_at, created_by, country_code, users, currency,
                                             yearly_total, company_name, contact_name, contact_email, contact_phone)
                values (:challenge, :at, :by, :country, :users, :currency, :total, :company, :contact, :email, :phone)""",
                new MapSqlParameterSource().addValue("challenge", challenge).addValue("at", Timestamp.from(now))
                        .addValue("by", user.id()).addValue("country", quote.countryCode())
                        .addValue("users", quote.users()).addValue("currency", quote.currency())
                        .addValue("total", quote.yearlyTotal()).addValue("company", request.companyName().strip())
                        .addValue("contact", request.contactName().strip())
                        .addValue("email", request.contactEmail().strip()).addValue("phone", phone));

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("Challenge", challenge);
        fields.put("Company", request.companyName().strip());
        fields.put("Contact", request.contactName().strip());
        fields.put("E-mail", request.contactEmail().strip());
        fields.put("Phone", phone == null ? "–" : phone);
        fields.put("Country", quote.countryName() + " (" + quote.countryCode() + ")");
        fields.put("Users", String.valueOf(quote.users()));
        fields.put("Price per user per month", quote.pricePerUserMonth().toPlainString() + " " + quote.currency()
                + " (" + quote.currencyName() + ")");
        fields.put("Yearly total", quote.yearlyTotal().toPlainString() + " " + quote.currency());
        fields.put("Calculation", calculation(quote));
        fields.put("Installation", baseUrl);
        fields.put("Requested by", user.getUsername());
        String subject = "BuggyWuggy quotation request: " + request.companyName().strip();
        String intro = "A BuggyWuggy administrator asks for a quotation for an annual donation. Answer with a donation "
                + "code for the challenge below.";
        Map<String, Object> details = details("quotation", challenge, request.companyName().strip(),
                request.contactName().strip(), request.contactEmail().strip(), phone, quote.countryCode(),
                quote.countryName(), quote.users(), quote.currency(), quote.yearlyTotal());
        details.put("requestedAt", now.truncatedTo(ChronoUnit.SECONDS).toString());
        details.put("requestedBy", user.getUsername());
        Map<String, Object> price = new LinkedHashMap<>();
        price.put("perUserMonth", quote.pricePerUserMonth());
        price.put("currencyName", quote.currencyName());
        price.put("basePerUserMonthEur", quote.basePricePerUserEur());
        price.put("bigMacPrice", quote.bigMacPrice());
        price.put("referenceBigMacPriceEur", quote.referenceBigMacPrice());
        price.put("bigMacIndexDate", quote.indexDate() == null ? null : quote.indexDate().toString());
        price.put("calculation", calculation(quote));
        details.put("price", price);
        String text = send(subject, intro, fields, details);
        return new QuotationResponse(challenge, quote, transport.configured(), SALES, subject, text);
    }

    static String calculation(Quote quote) {
        if (quote.bigMacPrice() == null) {
            return quote.basePricePerUserEur().toPlainString() + " EUR per user per month (no Big Mac index price)";
        }
        return quote.basePricePerUserEur().toPlainString() + " EUR × Big Mac " + quote.bigMacPrice().toPlainString()
                + " " + quote.currency() + " / Big Mac Netherlands " + quote.referenceBigMacPrice().toPlainString()
                + " EUR (Big Mac index of " + quote.indexDate() + ")";
    }

    /** What every {@value #ATTACHMENT} has; the caller adds what is particular to its kind. */
    private Map<String, Object> details(String kind, String challenge, String company, String contactName,
            String contactEmail, String contactPhone, String countryCode, String countryName, int users,
            String currency, BigDecimal yearlyTotal) {
        BuildVersionResponse build = version.version();
        Map<String, Object> contact = new LinkedHashMap<>();
        contact.put("name", contactName);
        contact.put("email", contactEmail);
        contact.put("phone", contactPhone);
        Map<String, Object> country = new LinkedHashMap<>();
        country.put("code", countryCode);
        country.put("name", countryName);
        Map<String, Object> installation = new LinkedHashMap<>();
        installation.put("url", baseUrl);
        installation.put("version", build.version());
        installation.put("commit", build.commit());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("format", FORMAT);
        details.put("kind", kind);
        details.put("challenge", challenge);
        details.put("company", company);
        details.put("contact", contact);
        details.put("country", country);
        details.put("users", users);
        details.put("currency", currency);
        details.put("yearlyTotal", yearlyTotal);
        details.put("installation", installation);
        return details;
    }

    /**
     * Queues a mail to {@link #SALES}, with {@code details} as {@value #ATTACHMENT}, and returns its
     * text. The contact person is the Reply-To: an answer (or a mailbox that makes issues of these
     * mails) reaches them, not the installation's own sender address.
     */
    private String send(String subject, String intro, Map<String, String> fields, Map<String, Object> details) {
        StringBuilder text = new StringBuilder(intro).append("\n\n");
        StringBuilder html = new StringBuilder("<p>").append(HtmlUtils.htmlEscape(intro)).append("</p><table>");
        fields.forEach((label, value) -> {
            text.append(label).append(": ").append(value).append('\n');
            html.append("<tr><td style=\"padding-right: 12px\"><b>").append(HtmlUtils.htmlEscape(label))
                    .append("</b></td><td>").append(HtmlUtils.htmlEscape(value)).append("</td></tr>");
        });
        html.append("</table>");
        byte[] attachment = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(details);
        Object contact = details.get("contact") instanceof Map<?, ?> c ? c.get("email") : null;
        outbox.enqueue(null, SALES, contact == null ? null : contact.toString(), Map.of(), subject, HtmlMail.document(html.toString()), text.toString(),
                new MailAttachment(ATTACHMENT, "application/json", attachment));
        return text.toString();
    }

    /**
     * Enters a donation code; it must answer one of the challenges of earlier requests. A code
     * entered before codes were kept may be entered again, to keep it.
     */
    public LicenseStatus activate(BuggyWuggyPrincipal user, ActivateRequest request) {
        List<String> challenges = jdbc.queryForList("""
                select challenge from license_request r
                where not exists (select 1 from license l where l.challenge = r.challenge and l.code is not null)
                order by created_at desc""", Map.of(), String.class);
        for (String challenge : challenges) {
            Optional<LocalDate> until = codes.verify(challenge, request.code());
            if (until.isPresent()) {
                if (until.get().isBefore(today())) {
                    throw badRequest("license.code-expired", until.get());
                }
                jdbc.update("""
                        insert into license (challenge, valid_until, activated_at, activated_by, code)
                        values (:challenge, :until, :at, :by, :code)
                        on conflict (challenge) do update set code = excluded.code""",
                        new MapSqlParameterSource().addValue("challenge", challenge).addValue("until", until.get())
                                .addValue("at", Timestamp.from(clock.instant())).addValue("by", user.id())
                                .addValue("code", request.code().replaceAll("\\s", "")));
                log.info("Donation code for {} entered by {}, valid until {}", challenge, user.getUsername(),
                        until.get());
                return status();
            }
        }
        throw badRequest("license.code-invalid");
    }

    /**
     * Once a day: when the current donation ends within a month, tells {@link #SALES} once, so
     * they can discuss a renewal.
     */
    @Scheduled(cron = "0 23 9 * * *")
    public void remindBeforeExpiry() {
        LocalDate today = today();
        jdbc.query("""
                select l.id, l.valid_until, r.challenge, r.company_name, r.contact_name, r.contact_email,
                       r.contact_phone, r.country_code, r.users, r.currency, r.yearly_total
                from license l join license_request r on r.challenge = l.challenge
                where l.reminder_sent_at is null
                  and l.valid_until between :today and :soon
                  and l.valid_until = (select max(valid_until) from license)""",
                Map.of("today", today, "soon", today.plusMonths(1)), rs -> {
                    Map<String, String> fields = new LinkedHashMap<>();
                    fields.put("Valid until", rs.getObject("valid_until", LocalDate.class).toString());
                    fields.put("Challenge", rs.getString("challenge"));
                    fields.put("Company", rs.getString("company_name"));
                    fields.put("Contact", rs.getString("contact_name"));
                    fields.put("E-mail", rs.getString("contact_email"));
                    String phone = rs.getString("contact_phone");
                    fields.put("Phone", phone == null ? "–" : phone);
                    fields.put("Country", rs.getString("country_code"));
                    fields.put("Users", String.valueOf(rs.getInt("users")));
                    fields.put("Yearly total", rs.getBigDecimal("yearly_total").toPlainString() + " "
                            + rs.getString("currency"));
                    fields.put("Installation", baseUrl);
                    String country = rs.getString("country_code");
                    Map<String, Object> details = details("renewal", rs.getString("challenge"),
                            rs.getString("company_name"), rs.getString("contact_name"), rs.getString("contact_email"),
                            phone, country, pricing.country(country).map(LicensePricing.Country::name).orElse(country), rs.getInt("users"),
                            rs.getString("currency"), rs.getBigDecimal("yearly_total"));
                    details.put("validUntil", fields.get("Valid until"));
                    send("BuggyWuggy donation expires on " + fields.get("Valid until") + ": "
                            + rs.getString("company_name"),
                            "The annual BuggyWuggy donation below expires within a month. Time to discuss a renewal.",
                            fields, details);
                    jdbc.update("update license set reminder_sent_at = :at where id = :id",
                            new MapSqlParameterSource().addValue("at", Timestamp.from(clock.instant()))
                                    .addValue("id", rs.getLong("id")));
                });
    }
}
