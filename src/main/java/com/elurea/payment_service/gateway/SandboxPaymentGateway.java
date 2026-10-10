package com.elurea.payment_service.gateway;

import com.elurea.payment_service.entity.Payment;
import com.elurea.payment_service.enums.PaymentProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Simulated provider for development, demos and automated tests: no money moves, no currency conversion.
 * The buyer "approves" through POST /api/payments/{id}/sandbox-result, which then goes through exactly
 * the same capture and success/failure handling as a real PayPal payment.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.payments.provider", havingValue = "sandbox", matchIfMissing = true)
public class SandboxPaymentGateway implements PaymentGateway {

    public SandboxPaymentGateway() {
        log.warn("Payment provider is SANDBOX: payments are simulated and no money is collected");
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.SANDBOX;
    }

    @Override
    public ProviderPayment create(Payment payment) {
        String id = "sbx_" + payment.getId().toString().replace("-", "");
        return new ProviderPayment(id, "https://sandbox.invalid/approve/" + id, payment.getAmount(), payment.getCurrency());
    }

    @Override
    public CaptureResult capture(Payment payment) {
        return new CaptureResult(CaptureResult.Outcome.COMPLETED, payment.getProviderPaymentId() + "_capture", null);
    }

    @Override
    public void refund(Payment payment) {
        log.info("Sandbox refund of {} {} for order {}", payment.getAmount(), payment.getCurrency(),
                payment.getOrderReference());
    }
}
