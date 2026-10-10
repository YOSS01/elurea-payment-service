package com.elurea.payment_service.entity;

import com.elurea.payment_service.enums.PaymentProvider;
import com.elurea.payment_service.enums.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One payment attempt for an order. The amount is copied from the order service at creation time,
 * never taken from the client. When the provider cannot charge in the shop currency (PayPal has no MAD),
 * the converted amount actually charged is recorded next to it.
 */
@Getter
@Setter
@Entity
@Table(name = "payment", indexes = {
        @Index(name = "idx_payment_order", columnList = "order_id"),
        @Index(name = "idx_payment_user_created", columnList = "user_id, created_at")
})
public class Payment extends BaseEntity {

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(nullable = false, length = 20, updatable = false)
    private String orderReference;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** Order total, in the shop currency. */
    @Column(nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    /** ISO 4217 code of the shop currency, e.g. "MAD". */
    @Column(nullable = false, length = 3, updatable = false)
    private String currency;

    /** What the provider actually charges (e.g. 15.57 EUR for 169.20 MAD). */
    @Column(precision = 12, scale = 2)
    private BigDecimal chargedAmount;

    @Column(length = 3)
    private String chargedCurrency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private PaymentProvider provider;

    /** Id at the provider (PayPal order id), set once the provider accepted the payment. */
    @Column(unique = true, length = 100)
    private String providerPaymentId;

    /** Id of the captured money at the provider (PayPal capture id), needed to refund. */
    @Column(length = 100)
    private String providerCaptureId;

    /** Where to send the buyer to approve the payment, when not using the provider's JavaScript buttons. */
    @Column(length = 500)
    private String approvalUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(length = 500)
    private String failureReason;

    private Instant paidAt;

    private Instant refundedAt;

    @Version
    private long version;
}
