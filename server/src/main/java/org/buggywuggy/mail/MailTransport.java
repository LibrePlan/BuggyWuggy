/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
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

    public MailTransport(MailProperties properties) {
        this.properties = properties;
        this.sender = properties.configured() ? createSender(properties) : null;
    }

    public boolean configured() {
        return sender != null;
    }

    public MailProperties properties() {
        return properties;
    }

    /** Sends a multipart mail with a plain-text and an HTML part. */
    public void send(String to, String subject, String html, String text) throws MailException {
        if (sender == null) {
            throw new MailPreparationException("Mail is not configured (buggywuggy.mail.host)");
        }
        MimeMessage message = sender.createMimeMessage();
        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
            helper.setFrom(properties.from());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(text, html);
            message.setHeader("Auto-Submitted", "auto-generated");
        } catch (MessagingException e) {
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
