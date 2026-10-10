package com.elurea.payment_service.controller;

import com.elurea.payment_service.gateway.PayPalPaymentGateway;
import com.elurea.payment_service.gateway.WebhookEvent;
import com.elurea.payment_service.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PayPal calls this endpoint for PAYMENT.CAPTURE.* events. In the PayPal developer dashboard, create a webhook
 * pointing to https://&lt;public host&gt;/api/payments/webhooks/paypal, subscribe it to PAYMENT.CAPTURE.COMPLETED
 * and PAYMENT.CAPTURE.DENIED, and set its id as PAYPAL_WEBHOOK_ID. The raw body is kept for verification.
 * Any non-2xx answer (e.g. 503 when the order service is down) makes PayPal retry later.
 */
@RestController
@RequestMapping("/api/payments/webhooks")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.payments.provider", havingValue = "paypal")
public class PayPalWebhookController {

    private final PayPalPaymentGateway paypal;
    private final PaymentService paymentService;

    @PostMapping("/paypal")
    public ResponseEntity<Void> handle(@RequestBody String payload, @RequestHeader HttpHeaders headers) {
        WebhookEvent event = paypal.parseWebhook(payload, headers);
        switch (event.kind()) {
            case SUCCEEDED -> paymentService.handleSucceeded(event.providerPaymentId(), event.captureId());
            case FAILED -> paymentService.handleFailed(event.providerPaymentId(), event.failureReason());
            case IGNORED -> { /* acknowledged, nothing to do */ }
        }
        return ResponseEntity.noContent().build();
    }
}
