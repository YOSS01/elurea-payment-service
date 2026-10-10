package com.elurea.payment_service.controller;

import com.elurea.payment_service.service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Service-to-service endpoints, authenticated with the internal API key (role SERVICE).
 * The /internal prefix must never be routed by the public gateway.
 */
@RestController
@RequestMapping("/internal/payments")
@RequiredArgsConstructor
public class InternalPaymentController {

    private final PaymentService paymentService;

    /** Order service, while cancelling a paid order. Idempotent. */
    @PostMapping("/orders/{orderId}/refund")
    public ResponseEntity<Void> refundOrder(@PathVariable UUID orderId) {
        paymentService.refundOrder(orderId);
        return ResponseEntity.noContent().build();
    }
}
