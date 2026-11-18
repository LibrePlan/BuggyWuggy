/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.mail.inbound;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.buggywuggy.mail.inbound.InboundMailProperties.Mailbox;
import org.buggywuggy.mail.inbound.InboundMailProperties.Security;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reads the configured mailboxes over IMAP, one after the other, each on its own: a mailbox that
 * cannot be reached does not hold up the others. Each mail is handled in a transaction of its
 * own and then moved (copied, and removed from the folder it was in) to the processed or spam
 * folder, which are created when missing. A mail that fails stays where it is and is tried
 * again at the next check.
 */
@Component
@EnableConfigurationProperties(InboundMailProperties.class)
public class InboundMailPoller {

    private static final Logger log = LoggerFactory.getLogger("org.buggywuggy.inbound-mail");

    /** How the last check of a mailbox went, for the administration page. */
    public record State(Instant lastCheck, Instant lastSuccess, String lastError, int handled) {
    }

    private final InboundMailProperties properties;
    private final InboundMailService service;
    private final Clock clock;
    private final Map<String, State> states = new ConcurrentHashMap<>();
    private final Map<String, Mailbox> mailboxes = new LinkedHashMap<>();
    private final Map<String, String> problems = new LinkedHashMap<>();

    public InboundMailPoller(InboundMailProperties properties, InboundMailService service, Clock clock) {
        this.properties = properties;
        this.service = service;
        this.clock = clock;
        Set<String> names = new HashSet<>();
        int n = 0;
        for (Mailbox box : properties.mailboxes()) {
            n++;
            String name = box.name() == null || box.name().isBlank() ? "mailbox-" + n : box.name().strip();
            String problem = problem(box);
            if (!names.add(name.toLowerCase(java.util.Locale.ROOT))) {
                problem = "another mailbox has the same name";
            }
            if (problem != null) {
                log.error("Inbound mail {}: not read, {}", name, problem);
                problems.put(name, problem);
            } else {
                mailboxes.put(name, box);
            }
        }
    }

    private static String problem(Mailbox box) {
        if (box.name() == null || box.name().isBlank()) {
            return "name is missing";
        }
        if (box.host() == null || box.host().isBlank()) {
            return "host is missing";
        }
        if (box.username() == null || box.username().isBlank()) {
            return "username is missing";
        }
        if (box.project() == null || box.project().isBlank()) {
            return "project is missing";
        }
        if (box.folder().equalsIgnoreCase(box.processedFolder()) || box.folder().equalsIgnoreCase(box.spamFolder())) {
            return "folder, processed-folder and spam-folder must differ";
        }
        return null;
    }

    /** The mailboxes as configured, in order: valid ones, and the problem of the others. */
    public List<Mailbox> mailboxes() {
        return properties.mailboxes();
    }

    public Map<String, String> problems() {
        return problems;
    }

    public State state(String name) {
        return states.get(name);
    }

    public Mailbox mailbox(String name) {
        return name == null ? null : mailboxes.get(name);
    }

    @Scheduled(fixedDelayString = "${buggywuggy.inbound-mail.poll-interval:2m}",
            initialDelayString = "${buggywuggy.inbound-mail.initial-delay:30s}")
    public void checkAll() {
        for (Mailbox box : mailboxes.values()) {
            check(box);
        }
    }

    /** Checks one mailbox; returns how many mails were handled. */
    public int check(Mailbox box) {
        Instant start = clock.instant();
        State before = states.get(box.name());
        int handled = 0;
        String error = null;
        try {
            handled = read(box);
        } catch (MessagingException | RuntimeException e) {
            error = message(e);
            log.warn("Inbound mail {}: checking {} on {} failed: {}", box.name(), box.folder(), box.host(), error);
        }
        states.put(box.name(), new State(start, error == null ? start : before == null ? null : before.lastSuccess(),
                error, handled));
        return handled;
    }

    private int read(Mailbox box) throws MessagingException {
        String protocol = box.security() == Security.SSL ? "imaps" : "imap";
        Properties props = new Properties();
        String timeout = String.valueOf(box.timeout().toMillis());
        props.put("mail." + protocol + ".connectiontimeout", timeout);
        props.put("mail." + protocol + ".timeout", timeout);
        props.put("mail." + protocol + ".writetimeout", timeout);
        if (box.security() == Security.STARTTLS) {
            props.put("mail.imap.starttls.enable", "true");
            props.put("mail.imap.starttls.required", "true");
        }
        if (box.security() != Security.NONE) {
            props.put("mail." + protocol + ".ssl.checkserveridentity", "true");
        }
        Session session = Session.getInstance(props);
        int handled = 0;
        List<String> failures = new ArrayList<>();
        try (Store store = session.getStore(protocol)) {
            store.connect(box.host(), box.port(), box.username(), box.password());
            Folder inbox = store.getFolder(box.folder());
            if (!inbox.exists()) {
                throw new MessagingException("Folder " + box.folder() + " does not exist");
            }
            Folder processed = folder(store, box.processedFolder());
            Folder spam = folder(store, box.spamFolder());
            inbox.open(Folder.READ_WRITE);
            try {
                for (Message message : inbox.getMessages()) {
                    if (message.isSet(Flags.Flag.DELETED)) {
                        continue;
                    }
                    try {
                        IncomingMail mail = IncomingMail.of(message, box.maxAttachmentSize().toBytes());
                        InboundMailService.Outcome outcome = service.handle(box, mail);
                        Folder target = outcome.action() == InboundMailService.Action.SPAM ? spam : processed;
                        inbox.copyMessages(new Message[] {message}, target);
                        message.setFlag(Flags.Flag.DELETED, true);
                        if (outcome.action() != InboundMailService.Action.ALREADY_HANDLED) {
                            handled++;
                        }
                    } catch (Exception e) {
                        failures.add(message(e));
                        log.warn("Inbound mail {}: a mail could not be handled and stays in {}: {}", box.name(),
                                box.folder(), message(e), e);
                    }
                }
            } finally {
                inbox.close(true);
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException(failures.size() + " mail(s) could not be handled: " + failures.getFirst());
        }
        return handled;
    }

    private static Folder folder(Store store, String name) throws MessagingException {
        Folder folder = store.getFolder(name);
        if (!folder.exists() && !folder.create(Folder.HOLDS_MESSAGES)) {
            throw new MessagingException("Folder " + name + " could not be created");
        }
        return folder;
    }

    private static String message(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String text = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }
}
