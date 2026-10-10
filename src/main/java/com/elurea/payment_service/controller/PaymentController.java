package com.elurea.payment_service.controller;

import com.elurea.payment_service.common.PageResponse;
import com.elurea.payment_service.common.SortWhitelist;
import com.elurea.payment_service.dto.CreatePaymentRequest;
import com.elurea.payment_service.dto.PaymentResponse;
import com.elurea.payment_service.dto.SandboxResultRequest;
import com.elurea.payment_service.enums.PaymentStatus;
import com.elurea.payment_service.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private static final Set<String> SORTABLE = Set.of("createdAt", "updatedAt", "amount", "status");

    private final PaymentService paymentService;

    /**
     * Starts the online payment of one of the caller's PENDING orders and returns the PayPal order id
     * (providerPaymentId, for the PayPal JavaScript buttons) and approvalUrl (for a redirect).
     * 201 for a new payment; 200 when an open payment for that order is returned instead.
     */
    @PostMapping
    public ResponseEntity<PaymentResponse> create(@Valid @RequestBody CreatePaymentRequest request) {
        PaymentService.CreatedPayment result = paymentService.create(request.orderId());
        if (result.created()) {
            return ResponseEntity.created(Locations.of(result.payment().id())).body(result.payment());
        }
        return ResponseEntity.ok(result.payment());
    }

    /** Customers get their own payments; administrators get all, filterable by order and status. */
    @GetMapping
    public PageResponse<PaymentResponse> getAll(
            @RequestParam(required = false) UUID orderId,
            @RequestParam(required = false) PaymentStatus status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return paymentService.getAll(orderId, status, SortWhitelist.validate(pageable, SORTABLE));
    }

    @GetMapping("/{id}")
    public PaymentResponse getById(@PathVariable UUID id) {
        return paymentService.getById(id);
    }

    /**
     * Call after the buyer approved the payment on PayPal (onApprove of the PayPal JavaScript buttons, or the
     * return page of the redirect flow). The payment is captured server-side with PayPal.
     */
    @PostMapping("/{id}/capture")
    public PaymentResponse capture(@PathVariable UUID id) {
        return paymentService.capture(id);
    }

    /** Back-office refund of a succeeded payment (e.g. returned goods). Cancelling an order refunds on its own. */
    @PostMapping("/{id}/refund")
    @PreAuthorize("hasRole('ADMIN')")
    public PaymentResponse refund(@PathVariable UUID id) {
        return paymentService.refundPayment(id);
    }

    /** Sandbox provider only (404 otherwise): simulates the customer paying or being declined. */
    @PostMapping("/{id}/sandbox-result")
    public PaymentResponse sandboxResult(@PathVariable UUID id, @Valid @RequestBody SandboxResultRequest request) {
        return paymentService.completeSandboxPayment(id, request.outcome());
    }
}
