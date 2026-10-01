package com.eve.diagnostics.dto.response;

/**
 * What we tell the provider. Always 2xx for an event we understood, even one we
 * deliberately ignored, so the provider stops retrying and we keep the audit
 * trail in webhook_events.processing_result instead.
 */
public record WebhookResponse(String result) {

    public static WebhookResponse of(String result) {
        return new WebhookResponse(result);
    }
}