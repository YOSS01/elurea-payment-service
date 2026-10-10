package com.elurea.payment_service.gateway;

import com.elurea.payment_service.entity.Payment;
import com.elurea.payment_service.enums.PaymentProvider;

import java.math.BigDecimal;

/**
 * A payment provider (PayPal, or the sandbox for development). Exactly one implementation is active,
 * chosen by app.payments.provider. Every call must be safe to retry for the same payment:
 * implementations use the payment id as idempotency key.
 */
public interface PaymentGateway {

    PaymentProvider provider();

    /** Opens the payment at the provider, converting the amount if the provider charges in another currency. */
    ProviderPayment create(Payment payment);

    /** Takes the money once the buyer has approved the payment. */
    CaptureResult capture(Payment payment);

    /** Refunds the full captured amount. */
    void refund(Payment payment);

    /**
     * @param providerPaymentId id the frontend passes to the provider's JavaScript SDK (PayPal order id)
     * @param approvalUrl       page where the buyer approves the payment (redirect flow)
     */
    record ProviderPayment(String providerPaymentId, String approvalUrl, BigDecimal chargedAmount,
                           String chargedCurrency) {
    }

    record CaptureResult(Outcome outcome, String captureId, String failureReason) {

        public enum Outcome {
            /** Money captured. */
            COMPLETED,
            /** Captured but held by the provider (e.g. under review): the final answer arrives by webhook. */
            PENDING,
            /** Refused (declined card, insufficient funds...). The buyer may try again. */
            DECLINED
        }
    }
}
