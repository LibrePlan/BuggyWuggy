/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.mail;

import java.util.List;
import java.util.Locale;
import org.buggywuggy.mail.IssueMail.Change;
import org.buggywuggy.mail.IssueMail.Model;
import org.buggywuggy.mail.IssueMail.Recipient;
import org.buggywuggy.mail.MailTemplateService.RenderedMail;
import org.buggywuggy.mail.MailTemplateService.Template;
import org.springframework.context.MessageSource;

/** Made-up values for previews and test mails of templates. */
final class SampleData {

    static final String INSTANCE_URL = "https://bugs.example.org";

    private SampleData() {
    }

    static RenderedMail render(MailTemplateKey key, Template template, Locale locale, MessageSource messages) {
        Model model = new Model("Ada Lovelace", "BUG-123", "Login page shows an error after a password reset",
                INSTANCE_URL + "/browse/BUG-123", "Bug", "In Progress", "High", "Ada Lovelace", "Grace Hopper",
                "Steps to reproduce:\n1. Reset the password\n2. Log in with the new password",
                "BUG", "Customer Portal",
                List.of(new Change("status", "Open", "In Progress"),
                        new Change("assignee", null, "Ada Lovelace"),
                        new Change("priority", "Medium", "High")),
                List.of("I can reproduce this in Firefox and Chrome.\nLooking into it now."),
                List.of("screenshot.png", "server.log"));
        Recipient recipient = new Recipient("Grace Hopper", "grace", INSTANCE_URL, INSTANCE_URL + "/profile");
        return IssueMail.render(key, template, key == MailTemplateKey.TEST_MAIL ? null : model, recipient, locale,
                messages);
    }
}
