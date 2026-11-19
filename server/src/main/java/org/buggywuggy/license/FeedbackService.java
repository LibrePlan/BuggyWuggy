/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.license;

import static org.buggywuggy.common.ApiException.forbidden;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.buggywuggy.common.BuildVersionController;
import org.buggywuggy.common.BuildVersionController.BuildVersionResponse;
import org.buggywuggy.demo.DemoMode;
import org.buggywuggy.license.LicenseService.Donor;
import org.buggywuggy.license.LicenseService.SupportStatus;
import org.buggywuggy.mail.HtmlMail;
import org.buggywuggy.mail.IssueMarkdown;
import org.buggywuggy.mail.MailAttachment;
import org.buggywuggy.mail.MailOutbox;
import org.buggywuggy.mail.MailTransport;
import org.buggywuggy.security.BuggyWuggyPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;
import tools.jackson.databind.json.JsonMapper;

/**
 * Feedback of the users of a supporting installation (Help → Send feedback): compliments, bug
 * reports, feature requests, service requests and training requests, mailed to {@link #TO}. Only
 * while a donation is valid today.
 *
 * <p>The mail proves where it comes from: it carries the challenge and the signed donation code
 * (headers {@code X-BuggyWuggy-Donor-Challenge} and {@code X-BuggyWuggy-Donor-Code}, and the
 * same lines in the text, for a mail sent by hand), which the receiving side verifies with the
 * public key of the donation codes. {@code X-BuggyWuggy-Feedback} says what it is. Reply-To is
 * the user's own address, so that an answer reaches them.
 *
 * <p>All details are also attached as {@value #ATTACHMENT} (see {@link #details}), so that the
 * receiving side can process the feedback without reading the text.
 */
@Service
@Transactional
public class FeedbackService {

    /** Where feedback goes. */
    public static final String TO = "feedback@buggywuggy.org";

    /** The name of the attached JSON file with all details. */
    public static final String ATTACHMENT = "buggywuggy-feedback.json";

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    public enum FeedbackType {
        COMPLIMENT("Compliment"),
        BUG("Bug report"),
        FEATURE("Feature request"),
        SERVICE("Service request"),
        TRAINING("Training request");

        private final String label;

        FeedbackType(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /**
     * {@code allowed}: whether this user may send feedback: a donation is valid today
     * ({@code donation} is ACTIVE) and its code is known ({@code codeMissing} otherwise), and the
     * user is not the shared demo account ({@code demoAccount}). {@code mailConfigured}: whether
     * BuggyWuggy sends the mail itself, or shows it to be sent by hand.
     */
    public record FeedbackStatus(boolean allowed, SupportStatus donation, boolean codeMissing, boolean demoAccount,
            boolean mailConfigured, String to) {
    }

    public record FeedbackRequest(
            @NotNull FeedbackType type,
            @NotBlank @Size(max = 200) String subject,
            @NotBlank @Size(max = 20_000) String message) {
    }

    /** The mail as it was queued, so it can also be sent by hand when BuggyWuggy cannot send mail. */
    public record FeedbackResponse(boolean mailConfigured, String to, String subject, String text) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final LicenseService licenses;
    private final MailOutbox outbox;
    private final MailTransport transport;
    private final BuildVersionController version;
    private final DemoMode demo;
    private final JsonMapper json;
    private final Clock clock;
    private final String baseUrl;

    public FeedbackService(NamedParameterJdbcTemplate jdbc, LicenseService licenses, MailOutbox outbox,
            MailTransport transport, BuildVersionController version, DemoMode demo, JsonMapper json, Clock clock,
            @Value("${buggywuggy.base-url}") String baseUrl) {
        this.jdbc = jdbc;
        this.licenses = licenses;
        this.outbox = outbox;
        this.transport = transport;
        this.version = version;
        this.demo = demo;
        this.json = json;
        this.clock = clock;
        this.baseUrl = baseUrl;
    }

    @Transactional(readOnly = true)
    public FeedbackStatus status(BuggyWuggyPrincipal user) {
        SupportStatus donation = licenses.supportStatus();
        boolean donor = licenses.donor().isPresent();
        // Visitors of a demo share one account: they could pass the donation code around.
        boolean demoAccount = demo.isDemoUser(user);
        return new FeedbackStatus(donor && !demoAccount, donation, donation == SupportStatus.ACTIVE && !donor,
                demoAccount, transport.configured(), TO);
    }

    /** Queues the feedback mail to {@link #TO}; 403 without a valid donation. */
    public FeedbackResponse send(BuggyWuggyPrincipal user, FeedbackRequest request) {
        if (demo.isDemoUser(user)) {
            throw forbidden("feedback.demo-account");
        }
        Donor donor = licenses.donor().orElseThrow(() -> forbidden("feedback.no-donation"));
        String email = jdbc.queryForObject("select email from user_account where id = :id", Map.of("id", user.id()),
                String.class);
        Optional<String> replyTo = address(email);
        BuildVersionResponse build = version.version();
        String buildVersion = build.version() + (build.commit() == null ? "" : " (" + build.commit() + ")");
        String summary = request.subject().strip().replaceAll("\\s+", " ");
        String subject = "BuggyWuggy " + request.type().label().toLowerCase(Locale.ROOT) + ": " + summary;

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("Type", request.type().label());
        fields.put("Subject", summary);
        fields.put("From", user.displayName() + " (" + user.getUsername() + ")"
                + replyTo.map(a -> " <" + a + ">").orElse(", no e-mail address"));
        fields.put("Company", donor.companyName());
        fields.put("Installation", baseUrl);
        fields.put("Version", buildVersion);
        fields.put("Donation valid until", donor.validUntil().toString());
        fields.put("Donor challenge", donor.challenge());
        fields.put("Donor code", donor.code());

        String message = request.message().strip();
        StringBuilder text = new StringBuilder("Feedback from a BuggyWuggy installation that supports the project.\n\n");
        StringBuilder html = new StringBuilder("<p>Feedback from a BuggyWuggy installation that supports the project.</p>"
                + "<table style=\"border-collapse: collapse; margin-bottom: 16px;\">");
        fields.forEach((label, value) -> {
            text.append(label).append(": ").append(value).append('\n');
            html.append("<tr><td style=\"padding: 2px 12px 2px 0; vertical-align: top;\"><b>")
                    .append(HtmlUtils.htmlEscape(label)).append("</b></td><td style=\"padding: 2px 0;\">")
                    .append(HtmlUtils.htmlEscape(value)).append("</td></tr>");
        });
        html.append("</table>");
        text.append('\n').append(message).append('\n');
        // The message is Markdown, as issue descriptions; links to the installation are made absolute.
        html.append("<div>").append(IssueMarkdown.toHtml(message, new IssueMarkdown.Links(baseUrl, baseUrl, Set.of())))
                .append("</div>");

        Map<String, String> headers = new LinkedHashMap<>();
        // Written by a person, not generated: an answer is welcome.
        headers.put("Auto-Submitted", "no");
        headers.put("X-BuggyWuggy-Feedback", request.type().name().toLowerCase(Locale.ROOT));
        headers.put("X-BuggyWuggy-Version", buildVersion);
        headers.put("X-BuggyWuggy-Installation", baseUrl);
        headers.put("X-BuggyWuggy-Donor-Challenge", donor.challenge());
        headers.put("X-BuggyWuggy-Donor-Code", donor.code());

        byte[] details = json.writerWithDefaultPrettyPrinter().writeValueAsBytes(
                details(request, summary, message, user, replyTo.orElse(null), donor, build));
        outbox.enqueue(null, TO, replyTo.orElse(null), headers, subject, HtmlMail.document(html.toString()),
                text.toString(), new MailAttachment(ATTACHMENT, "application/json", details));
        log.info("Feedback ({}) by {} queued for {}", request.type().label(), user.getUsername(), TO);
        return new FeedbackResponse(transport.configured(), TO, subject, text.toString());
    }

    /**
     * Everything about the feedback, for the attachment: what it is, who sent it, from which
     * installation, and the donor proof. {@code format} changes when fields change meaning.
     */
    private Map<String, Object> details(FeedbackRequest request, String summary, String message,
            BuggyWuggyPrincipal user, String email, Donor donor, BuildVersionResponse build) {
        Map<String, Object> from = new LinkedHashMap<>();
        from.put("name", user.displayName());
        from.put("username", user.getUsername());
        from.put("email", email);
        Map<String, Object> installation = new LinkedHashMap<>();
        installation.put("url", baseUrl);
        installation.put("version", build.version());
        installation.put("commit", build.commit());
        Map<String, Object> donation = new LinkedHashMap<>();
        donation.put("company", donor.companyName());
        donation.put("validUntil", donor.validUntil().toString());
        donation.put("challenge", donor.challenge());
        donation.put("code", donor.code());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("format", "buggywuggy-feedback/1");
        details.put("type", request.type().name().toLowerCase(Locale.ROOT));
        details.put("typeLabel", request.type().label());
        details.put("subject", summary);
        details.put("message", message);
        details.put("sentAt", clock.instant().truncatedTo(ChronoUnit.SECONDS).toString());
        details.put("from", from);
        details.put("installation", installation);
        details.put("donation", donation);
        return details;
    }

    /** A valid address for Reply-To, if the user has one. */
    private static Optional<String> address(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        try {
            InternetAddress address = new InternetAddress(email.strip(), true);
            return Optional.of(address.getAddress());
        } catch (AddressException e) {
            return Optional.empty();
        }
    }
}
