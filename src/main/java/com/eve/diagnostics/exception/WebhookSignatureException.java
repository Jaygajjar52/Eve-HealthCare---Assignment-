package com.eve.diagnostics.exception;

/** The X-Signature header did not match the body. Answered with 401. */
public class WebhookSignatureException extends RuntimeException {

    public WebhookSignatureException() {
        super("Invalid webhook signature.");
    }
}