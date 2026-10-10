package com.elurea.payment_service.gateway;

/**
 * Provider notification, reduced to what matters to us.
 *
 * @param providerPaymentId the provider's payment id (PayPal order id)
 * @param captureId         the provider's capture id, for successful captures
 */
public record WebhookEvent(Kind kind, String providerPaymentId, String captureId, String failureReason) {

    public enum Kind {
        SUCCEEDED,
        FAILED,
        /** Event types we do not act on; acknowledged so the provider stops resending them. */
        IGNORED
    }
}
