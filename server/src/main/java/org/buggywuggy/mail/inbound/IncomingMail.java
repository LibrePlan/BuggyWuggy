/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.mail.inbound;

import jakarta.mail.Address;
import jakarta.mail.BodyPart;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeUtility;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.buggywuggy.mail.HtmlMail;

/**
 * A mail as BuggyWuggy reads it: who sent it (Reply-To before From, so that a mail the feedback
 * form sent for someone shows that person), the text (the plain-text part, else the HTML part
 * as text) and the files. Files larger than the limit are listed in {@code skipped}.
 */
record IncomingMail(String messageId, String sender, String senderName, String subject, Instant sentAt,
        Map<String, String> headers, String text, List<File> files, List<String> skipped) {

    record File(String name, String contentType, byte[] content) {
    }

    String header(String name) {
        return headers.get(name);
    }

    static IncomingMail of(Message message, long maxFileBytes) throws MessagingException, IOException {
        Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (var e = message.getAllHeaders(); e.hasMoreElements(); ) {
            var h = e.nextElement();
            headers.putIfAbsent(h.getName(), decode(h.getValue()));
        }
        InternetAddress from = first(message.getHeader("Reply-To") != null ? message.getReplyTo() : null);
        if (from == null) {
            from = first(message.getFrom());
        }
        String sender = from == null || from.getAddress() == null ? null : from.getAddress().strip();
        String name = from == null || from.getPersonal() == null || from.getPersonal().isBlank() ? null
                : from.getPersonal().strip();
        Instant sent = message.getSentDate() == null ? null : message.getSentDate().toInstant();
        String subject = message.getSubject() == null ? "" : message.getSubject().strip();

        Body body = new Body(maxFileBytes);
        body.walk(message);
        String text = body.plain != null ? body.plain : body.html != null ? HtmlMail.text(body.html) : "";

        String id = headers.get("Message-ID");
        if (id == null || id.isBlank()) {
            // Without Message-ID: the same mail gives the same id, so that it is not handled twice.
            id = "<sha256-" + sha256(sender + "|" + sent + "|" + subject + "|" + text) + "@buggywuggy>";
        }
        return new IncomingMail(id.strip(), sender, name, subject, sent, headers, text.strip(), body.files,
                body.skipped);
    }

    private static InternetAddress first(Address[] addresses) {
        if (addresses == null) {
            return null;
        }
        for (Address a : addresses) {
            if (a instanceof InternetAddress internet) {
                return internet;
            }
        }
        return null;
    }

    private static String decode(String value) {
        try {
            return value == null ? null : MimeUtility.decodeText(MimeUtility.unfold(value));
        } catch (IOException e) {
            return value;
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Walks the MIME tree: the first text parts are the body, everything else a file. */
    private static final class Body {
        private final long maxFileBytes;
        private String plain;
        private String html;
        private final List<File> files = new ArrayList<>();
        private final List<String> skipped = new ArrayList<>();

        Body(long maxFileBytes) {
            this.maxFileBytes = maxFileBytes;
        }

        void walk(Part part) throws MessagingException, IOException {
            String type = part.getContentType() == null ? "text/plain" : part.getContentType();
            String fileName = fileName(part);
            boolean attachment = Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition()) || fileName != null;
            if (part.isMimeType("multipart/*") && part.getContent() instanceof Multipart multipart) {
                for (int i = 0; i < multipart.getCount(); i++) {
                    BodyPart child = multipart.getBodyPart(i);
                    walk(child);
                }
                return;
            }
            if (!attachment && part.isMimeType("text/plain") && plain == null) {
                plain = text(part, type);
                return;
            }
            if (!attachment && part.isMimeType("text/html") && html == null) {
                html = text(part, type);
                return;
            }
            if (!attachment && (part.isMimeType("text/plain") || part.isMimeType("text/html"))) {
                // A second text part (e.g. after a file): part of the body.
                String more = part.isMimeType("text/html") ? HtmlMail.text(text(part, type)) : text(part, type);
                if (plain != null) {
                    plain = plain + "\n\n" + more;
                } else {
                    html = (html == null ? "" : html) + "<pre>" + org.springframework.web.util.HtmlUtils.htmlEscape(more)
                            + "</pre>";
                }
                return;
            }
            String name = fileName != null ? fileName
                    : part.isMimeType("message/rfc822") ? "message-" + (files.size() + 1) + ".eml"
                    : "part-" + (files.size() + 1) + extension(type);
            byte[] content = read(part, maxFileBytes);
            if (content == null) {
                skipped.add(name);
            } else {
                files.add(new File(name, baseType(type), content));
            }
        }

        private static String fileName(Part part) throws MessagingException {
            String name = part.getFileName();
            if (name == null || name.isBlank()) {
                return null;
            }
            try {
                return MimeUtility.decodeText(name).strip();
            } catch (IOException e) {
                return name.strip();
            }
        }

        private static String text(Part part, String type) throws MessagingException, IOException {
            Object content;
            try {
                content = part.getContent();
            } catch (IOException e) {
                content = null;
            }
            if (content instanceof String s) {
                return s;
            }
            // An unknown character set: read the bytes as UTF-8, with replacement characters.
            Charset charset = StandardCharsets.UTF_8;
            try {
                String name = new ContentType(type).getParameter("charset");
                if (name != null) {
                    charset = Charset.forName(MimeUtility.javaCharset(name));
                }
            } catch (Exception e) {
                // UTF-8
            }
            try (InputStream in = part.getInputStream()) {
                return new String(in.readAllBytes(), charset);
            }
        }

        /** The content, or null when it is larger than {@code max}. */
        private static byte[] read(Part part, long max) throws MessagingException, IOException {
            try (InputStream in = part.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                long total = 0;
                for (int n; (n = in.read(buffer)) > 0; ) {
                    total += n;
                    if (total > max) {
                        return null;
                    }
                    out.write(buffer, 0, n);
                }
                return out.toByteArray();
            }
        }

        private static String baseType(String type) {
            try {
                return new ContentType(type).getBaseType().toLowerCase(Locale.ROOT);
            } catch (Exception e) {
                return "application/octet-stream";
            }
        }

        private static String extension(String type) {
            String base = baseType(type);
            return switch (base) {
                case "image/png" -> ".png";
                case "image/jpeg" -> ".jpg";
                case "image/gif" -> ".gif";
                case "application/pdf" -> ".pdf";
                case "text/calendar" -> ".ics";
                default -> ".bin";
            };
        }
    }
}
