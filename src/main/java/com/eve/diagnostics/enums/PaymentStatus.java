package com.eve.diagnostics.enums;

/**
 * Outcome of a payment attempt. Deliberately only two values: this is a mock
 * gateway, and anything more (REFUNDED, PARTIALLY_REFUNDED) would be a refund
 * flow, which is listed as future work in the README.
 */
public enum PaymentStatus {
    SUCCESS,
    FAILED
}