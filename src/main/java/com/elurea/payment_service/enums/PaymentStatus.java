package com.elurea.payment_service.enums;

/**
 * State of one payment attempt. An order may have several attempts (e.g. a declined card, then a retry),
 * but at most one SUCCEEDED attempt: any duplicate success is refunded automatically.
 */
public enum PaymentStatus {
    /** Created at the provider, waiting for the customer to complete it. */
    PENDING,
    SUCCEEDED,
    FAILED,
    REFUNDED
}
