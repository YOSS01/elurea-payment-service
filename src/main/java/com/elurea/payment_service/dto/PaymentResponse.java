package com.elurea.payment_service.dto;

import com.elurea.payment_service.entity.Payment;
import com.elurea.payment_service.enums.PaymentProvider;
import com.elurea.payment_service.enums.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * providerPaymentId is the PayPal order id the frontend gives to the PayPal JavaScript SDK (createOrder);
 * approvalUrl is the alternative redirect page. Both are only returned to the payment's owner while PENDING.
 * amount/currency is the order total in the shop currency; chargedAmount/chargedCurrency is what PayPal charges.
 */
public record PaymentResponse(
        UUID id,
        UUID orderId,
        String orderReference,
        UUID userId,
        BigDecimal amount,
        String currency,
        BigDecimal chargedAmount,
        String chargedCurrency,
        PaymentProvider provider,
        PaymentStatus status,
        String providerPaymentId,
        String approvalUrl,
        String failureReason,
        Instant paidAt,
        Instant refundedAt,
        Instant createdAt,
        Instant updatedAt
) {

    public static PaymentResponse from(Payment payment, boolean viewedByOwner) {
        boolean showCheckoutData = viewedByOwner && payment.getStatus() == PaymentStatus.PENDING;
        return new PaymentResponse(
                payment.getId(),
                payment.getOrderId(),
                payment.getOrderReference(),
                payment.getUserId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getChargedAmount(),
                payment.getChargedCurrency(),
                payment.getProvider(),
                payment.getStatus(),
                showCheckoutData ? payment.getProviderPaymentId() : null,
                showCheckoutData ? payment.getApprovalUrl() : null,
                payment.getFailureReason(),
                payment.getPaidAt(),
                payment.getRefundedAt(),
                payment.getCreatedAt(),
                payment.getUpdatedAt()
        );
    }
}
