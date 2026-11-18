/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import org.buggywuggy.demo.DemoMode;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.mail.MailException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/** Sends one mail over SMTP, configured by {@link MailProperties}. */
@Component
@EnableConfigurationProperties(MailProperties.class)
public class MailTransport {

    private final MailProperties properties;
    private final JavaMailSenderImpl sender;
    private final boolean demo;

    public MailTransport(MailProperties properties, DemoMode demo) {
        this.properties = properties;
        this.demo = demo.enabled();
        // In demo mode BuggyWuggy sends no mail at all, whatever the configuration says.
        this.sender = !this.demo && properties.configured() ? createSender(properties) : null;
    }

    /** Demo mode: mail is switched off. */
    public boolean demo() {
        return demo;
    }

    public boolean configured() {
        return sender != null;
    }

    public MailProperties properties() {
        return properties;
    }

    /** Sends a multipart mail with a plain-text and an HTML part. */
    public void send(String to, String subject, String html, String text) throws MailException {
        send(to, null, Map.of(), subject, html, text);
    }

    /**
     * Sends a multipart mail with a plain-text and an HTML part, with a Reply-To (when not null)
     * and extra headers. A mail is marked as automatically generated unless {@code headers}
     * says otherwise ({@code Auto-Submitted: no} for a mail that a person wrote).
     */
    public void send(String to, String replyTo, Map<String, String> headers, String subject, String html,
            String text) throws MailException {
        if (sender == null) {
            throw new MailPreparationException(demo ? "Mail is switched off in demo mode"
                    : "Mail is not configured (buggywuggy.mail.host)");
        }
        // A Message-ID of our own (external comments: answers are recognised by it) is kept;
        // otherwise the mail library makes one when the mail is sent.
        String messageId = headers.get("Message-ID");
        MimeMessage message = messageId == null ? sender.createMimeMessage()
                : new MimeMessage(sender.getSession()) {
                    @Override
                    protected void updateMessageID() throws MessagingException {
                        setHeader("Message-ID", messageId.replaceAll("[\\r\\n]+", ""));
                    }
                };
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.from());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, html);
            if (replyTo != null) {
                helper.setReplyTo(replyTo);
            }
            message.setHeader("Auto-Submitted", "auto-generated");
            for (Map.Entry<String, String> header : headers.entrySet()) {
                if (header.getKey().equalsIgnoreCase("Message-ID")) {
                    continue;
                }
                // No line breaks: a value must not add headers of its own.
                String value = header.getValue().replaceAll("[\\r\\n]+", " ");
                message.setHeader(header.getKey(), MimeUtility.fold(header.getKey().length() + 2,
                        MimeUtility.encodeText(value, StandardCharsets.UTF_8.name(), "Q")));
            }
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new MailPreparationException(e.getMessage(), e);
        }
        sender.send(message);
    }

    private static JavaMailSenderImpl createSender(MailProperties p) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(p.host().strip());
        sender.setPort(p.port());
        sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
        boolean auth = p.username() != null && !p.username().isBlank();
        if (auth) {
            sender.setUsername(p.username());
            sender.setPassword(p.password());
        }
        Properties props = sender.getJavaMailProperties();
        String timeout = String.valueOf(p.timeout().toMillis());
        props.put("mail.smtp.auth", String.valueOf(auth));
        props.put("mail.smtp.connectiontimeout", timeout);
        props.put("mail.smtp.timeout", timeout);
        props.put("mail.smtp.writetimeout", timeout);
        switch (p.security()) {
            case STARTTLS -> {
                props.put("mail.smtp.starttls.enable", "true");
                props.put("mail.smtp.starttls.required", "true");
            }
            case SSL -> {
                sender.setProtocol("smtps");
                props.put("mail.smtps.auth", String.valueOf(auth));
                props.put("mail.smtps.connectiontimeout", timeout);
                props.put("mail.smtps.timeout", timeout);
                props.put("mail.smtps.writetimeout", timeout);
            }
            case NONE -> {
                // Plain SMTP, e.g. a local relay.
            }
        }
        return sender;
    }
}
