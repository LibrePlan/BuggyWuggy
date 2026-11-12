/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.mail;

import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.buggywuggy.mail.MailTemplateService.RenderedMail;
import org.buggywuggy.mail.MailTemplateService.Template;
import org.jsoup.nodes.Entities;
import org.springframework.context.MessageSource;

/** The values of the keywords of issue mails, and the HTML of their block keywords. */
public final class IssueMail {

    /** One field change; texts may be {@code null} for "nothing". */
    public record Change(String field, String from, String to) {
    }

    /** Everything an issue mail can show. Nullable: assignee, priority, description. */
    public record Model(String actorName, String issueKey, String summary, String issueUrl, String issueType,
            String status, String priority, String assignee, String reporter, String description,
            String projectKey, String projectName, List<Change> changes, List<String> comments,
            List<String> attachments) {
    }

    /** Who the mail is for and where BuggyWuggy is. */
    public record Recipient(String name, String username, String instanceUrl, String profileUrl) {
    }

    /** Long values (descriptions) are cut in the list of changes. */
    private static final int MAX_CHANGE_TEXT = 1000;

    private IssueMail() {
    }

    public static RenderedMail render(MailTemplateKey key, Template template, Model model, Recipient recipient,
            Locale locale, MessageSource messages) {
        return MailTemplateService.render(key, template, values(model, recipient, locale, messages),
                blocks(model, locale, messages));
    }

    public static Function<String, String> values(Model m, Recipient r, Locale locale, MessageSource messages) {
        return name -> switch (name) {
            case "recipient.name" -> r.name();
            case "recipient.username" -> r.username();
            case "instance.url" -> r.instanceUrl();
            case "profile.url" -> r.profileUrl();
            case "actor.name" -> m == null ? null : m.actorName();
            case "issue.key" -> m == null ? null : m.issueKey();
            case "issue.summary" -> m == null ? null : m.summary();
            case "issue.url" -> m == null ? null : m.issueUrl();
            case "issue.type" -> m == null ? null : m.issueType();
            case "issue.status" -> m == null ? null : m.status();
            case "issue.priority" -> m == null ? null : orNone(m.priority(), locale, messages);
            case "issue.assignee" -> m == null ? null
                    : m.assignee() != null ? m.assignee() : text("mail.value.unassigned", locale, messages);
            case "issue.reporter" -> m == null ? null : orNone(m.reporter(), locale, messages);
            case "project.key" -> m == null ? null : m.projectKey();
            case "project.name" -> m == null ? null : m.projectName();
            case "attachments" -> m == null ? null : String.join(", ", m.attachments());
            default -> null;
        };
    }

    public static Function<String, String> blocks(Model m, Locale locale, MessageSource messages) {
        return name -> {
            if (m == null) {
                return "";
            }
            return switch (name) {
                case "changes" -> changesTable(m.changes(), locale, messages);
                case "comments" -> {
                    StringBuilder html = new StringBuilder();
                    for (String comment : m.comments()) {
                        html.append("<div style=\"margin: 8px 0; padding: 4px 12px; border-left: 3px solid #d0d7de;\">")
                                .append(multiline(comment)).append("</div>");
                    }
                    yield html.toString();
                }
                case "issue.description" -> m.description() == null || m.description().isBlank() ? ""
                        : "<div>" + multiline(m.description()) + "</div>";
                default -> "";
            };
        };
    }

    private static String changesTable(List<Change> changes, Locale locale, MessageSource messages) {
        if (changes.isEmpty()) {
            return "";
        }
        StringBuilder html = new StringBuilder("<table cellpadding=\"4\" cellspacing=\"0\" style=\"border-collapse: collapse;\">");
        for (Change c : changes) {
            String label = messages.getMessage("mail.field." + c.field(), null, c.field(), locale);
            html.append("<tr><td valign=\"top\" style=\"color: #57606a; padding-right: 16px;\">")
                    .append(Entities.escape(label)).append("</td><td>");
            if (c.from() != null && !c.from().isBlank()) {
                html.append("<del style=\"color: #8c959f;\">").append(multiline(cut(c.from()))).append("</del> → ");
            }
            html.append(c.to() == null || c.to().isBlank()
                    ? "<i>" + Entities.escape(text("mail.value.none", locale, messages)) + "</i>"
                    : multiline(cut(c.to())));
            html.append("</td></tr>");
        }
        return html.append("</table>").toString();
    }

    private static String multiline(String text) {
        return Entities.escape(text).replace("\r\n", "\n").replace("\n", "<br>");
    }

    private static String cut(String text) {
        return text.length() > MAX_CHANGE_TEXT ? text.substring(0, MAX_CHANGE_TEXT - 1) + "…" : text;
    }

    private static String orNone(String value, Locale locale, MessageSource messages) {
        return value != null ? value : text("mail.value.none", locale, messages);
    }

    private static String text(String code, Locale locale, MessageSource messages) {
        return messages.getMessage(code, null, code, locale);
    }
}
