/*
 * BuggyWuggy — open source, self-hosted issue tracker
 * Copyright (C) 2026 The BuggyWuggy contributors
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */
package org.buggywuggy.webhook;

import jakarta.validation.Valid;
import java.util.List;
import org.buggywuggy.webhook.WebhookService.DeliveryResponse;
import org.buggywuggy.webhook.WebhookService.WebhookRequest;
import org.buggywuggy.webhook.WebhookService.WebhookResponse;
import org.buggywuggy.webhook.WebhookService.WebhookSecretResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Outgoing webhooks, for administrators (/api/v1/admin/** needs ADMINISTER). */
@RestController
@RequestMapping("/api/v1/admin/webhooks")
public class WebhookController {

    private final WebhookService webhooks;

    public WebhookController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @GetMapping
    public List<WebhookResponse> listWebhooks() {
        return webhooks.list();
    }

    /** The events a webhook can subscribe to. */
    @GetMapping("/events")
    public List<String> webhookEvents() {
        return WebhookService.EVENTS;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WebhookSecretResponse createWebhook(@Valid @RequestBody WebhookRequest request) {
        return webhooks.create(request);
    }

    @GetMapping("/{id}")
    public WebhookResponse getWebhook(@PathVariable long id) {
        return webhooks.get(id);
    }

    @PutMapping("/{id}")
    public WebhookResponse updateWebhook(@PathVariable long id, @Valid @RequestBody WebhookRequest request) {
        return webhooks.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteWebhook(@PathVariable long id) {
        webhooks.delete(id);
    }

    @PostMapping("/{id}/secret")
    public WebhookSecretResponse rotateWebhookSecret(@PathVariable long id) {
        return webhooks.rotateSecret(id);
    }

    @GetMapping("/{id}/deliveries")
    public List<DeliveryResponse> webhookDeliveries(@PathVariable long id) {
        return webhooks.deliveries(id);
    }

    @PostMapping("/{id}/test")
    public DeliveryResponse testWebhook(@PathVariable long id) {
        return webhooks.test(id);
    }

    @PostMapping("/{id}/deliveries/{deliveryId}/retry")
    public DeliveryResponse retryWebhookDelivery(@PathVariable long id, @PathVariable long deliveryId) {
        return webhooks.retry(id, deliveryId);
    }
}
