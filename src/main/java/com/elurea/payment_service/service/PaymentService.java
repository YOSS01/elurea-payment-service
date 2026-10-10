package com.elurea.payment_service.service;

import com.elurea.payment_service.client.OrderServiceClient;
import com.elurea.payment_service.client.OrderServiceClient.OrderRejectedPaymentException;
import com.elurea.payment_service.client.OrderServiceClient.OrderView;
import com.elurea.payment_service.common.PageResponse;
import com.elurea.payment_service.config.AppProperties;
import com.elurea.payment_service.dto.PaymentResponse;
import com.elurea.payment_service.dto.SandboxResultRequest;
import com.elurea.payment_service.entity.Payment;
import com.elurea.payment_service.enums.PaymentProvider;
import com.elurea.payment_service.enums.PaymentStatus;
import com.elurea.payment_service.exception.ConflictException;
import com.elurea.payment_service.exception.ResourceNotFoundException;
import com.elurea.payment_service.gateway.PaymentGateway;
import com.elurea.payment_service.repository.PaymentRepository;
import com.elurea.payment_service.security.CurrentUser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Online payments for orders.
 * <p>
 * Rules that protect the customer's money:
 * <ul>
 *     <li>the amount always comes from the order service, never from the client;</li>
 *     <li>only the provider (server-side capture or verified webhook) can declare a payment successful;</li>
 *     <li>an order has at most one successful payment: a duplicate success is refunded automatically;</li>
 *     <li>a payment completing on an order that was cancelled meanwhile is refunded automatically.</li>
 * </ul>
 * All state changes for one order run under a row lock on that order's payments. Remote calls to the
 * order service happen outside those locks, so the two services can never wait on each other.
 */
@Slf4j
@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final PaymentGateway gateway;
    private final OrderServiceClient orderClient;
    private final TransactionTemplate tx;
    private final String currency;

    public PaymentService(PaymentRepository paymentRepository, PaymentGateway gateway, OrderServiceClient orderClient,
                          TransactionTemplate transactionTemplate, AppProperties properties) {
        this.paymentRepository = paymentRepository;
        this.gateway = gateway;
        this.orderClient = orderClient;
        this.tx = transactionTemplate;
        this.currency = properties.payments().currency().toUpperCase(Locale.ROOT);
    }

    /**
     * Starts (or resumes) the online payment of one of the caller's orders.
     * Calling it again while a payment is still open returns that same payment instead of opening another.
     */
    public CreatedPayment create(UUID orderId) {
        UUID userId = CurrentUser.requireId();
        OrderView order = orderClient.getOrder(orderId, CurrentUser.requireToken());

        if (!"ONLINE".equals(order.paymentMethod())) {
            throw new ConflictException("Order %s is paid on delivery, not online".formatted(order.reference()));
        }
        if (!"UNPAID".equals(order.paymentStatus())) {
            throw new ConflictException("Order %s is already %s".formatted(order.reference(), order.paymentStatus()));
        }
        if (!"PENDING".equals(order.status())) {
            throw new ConflictException("Order %s is %s and cannot be paid".formatted(order.reference(), order.status()));
        }

        Optional<Payment> open = paymentRepository.findFirstByOrderIdAndStatusOrderByCreatedAtDesc(orderId, PaymentStatus.PENDING)
                .filter(payment -> payment.getProvider() == gateway.provider()
                        && payment.getProviderPaymentId() != null
                        && payment.getAmount().compareTo(order.totalPrice()) == 0);
        if (open.isPresent()) {
            return new CreatedPayment(PaymentResponse.from(open.get(), true), false);
        }

        Payment payment = new Payment();
        payment.setOrderId(orderId);
        payment.setOrderReference(order.reference());
        payment.setUserId(userId);
        payment.setAmount(order.totalPrice());
        payment.setCurrency(currency);
        payment.setProvider(gateway.provider());
        payment = paymentRepository.saveAndFlush(payment); // the id is the provider's idempotency key

        try {
            PaymentGateway.ProviderPayment opened = gateway.create(payment);
            payment.setProviderPaymentId(opened.providerPaymentId());
            payment.setApprovalUrl(opened.approvalUrl());
            payment.setChargedAmount(opened.chargedAmount());
            payment.setChargedCurrency(opened.chargedCurrency());
        } catch (RuntimeException ex) {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason("The payment provider could not be reached");
            paymentRepository.saveAndFlush(payment);
            throw ex;
        }
        return new CreatedPayment(PaymentResponse.from(paymentRepository.saveAndFlush(payment), true), true);
    }

    public PaymentResponse getById(UUID id) {
        Payment payment = findPayment(id);
        CurrentUser.requireSelfOrAdmin(payment.getUserId());
        return PaymentResponse.from(payment, isOwner(payment));
    }

    /** Administrators see every payment (optionally filtered); customers only their own. */
    public PageResponse<PaymentResponse> getAll(UUID orderId, PaymentStatus status, Pageable pageable) {
        UUID owner = CurrentUser.isAdmin() ? null : CurrentUser.requireId();
        return PageResponse.of(paymentRepository.search(owner, orderId, status, pageable),
                payment -> PaymentResponse.from(payment, isOwner(payment)));
    }

    /**
     * Called by the frontend after the buyer approved the payment on PayPal: we capture it with PayPal,
     * server-side. The browser's word is never trusted; only PayPal's capture answer changes the status.
     * Safe to call twice (double click, retry): the capture is idempotent at PayPal and here.
     */
    public PaymentResponse capture(UUID id) {
        Payment payment = findPayment(id);
        CurrentUser.requireSelfOrAdmin(payment.getUserId());
        if (payment.getStatus() == PaymentStatus.PENDING) {
            if (payment.getProvider() != gateway.provider()) {
                throw new ConflictException("This payment was opened with another provider, start a new one");
            }
            PaymentGateway.CaptureResult result = gateway.capture(payment);
            switch (result.outcome()) {
                case COMPLETED -> handleSucceeded(payment.getProviderPaymentId(), result.captureId());
                case DECLINED -> handleFailed(payment.getProviderPaymentId(), result.failureReason());
                case PENDING -> log.info("Capture of payment {} is pending at the provider, waiting for its webhook", id);
            }
        } else if (payment.getStatus() != PaymentStatus.SUCCEEDED) {
            throw new ConflictException("This payment is already %s".formatted(payment.getStatus()));
        }
        return PaymentResponse.from(findPayment(id), isOwner(payment));
    }

    /** Provider says the money was captured (capture call, webhook or sandbox). Safe to receive several times. */
    public void handleSucceeded(String providerPaymentId, String captureId) {
        Payment payment = tx.execute(status -> markSucceeded(providerPaymentId, captureId));
        if (payment != null && payment.getStatus() == PaymentStatus.SUCCEEDED) {
            confirmToOrder(payment);
        }
    }

    /** Provider says the attempt failed (declined card, cancelled...). The customer may try again. */
    public void handleFailed(String providerPaymentId, String reason) {
        tx.executeWithoutResult(status -> lockPayment(providerPaymentId).ifPresent(payment -> {
            if (payment.getStatus() == PaymentStatus.PENDING) {
                payment.setStatus(PaymentStatus.FAILED);
                payment.setFailureReason(reason);
            }
        }));
    }

    /** Sandbox only: the owner simulates the outcome at the bank. */
    public PaymentResponse completeSandboxPayment(UUID id, SandboxResultRequest.Outcome outcome) {
        Payment payment = findPayment(id);
        CurrentUser.requireSelfOrAdmin(payment.getUserId());
        if (gateway.provider() != PaymentProvider.SANDBOX || payment.getProvider() != PaymentProvider.SANDBOX) {
            throw new ResourceNotFoundException("Sandbox payment", id);
        }
        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new ConflictException("This payment is already %s".formatted(payment.getStatus()));
        }
        return switch (outcome) {
            case SUCCEEDED -> capture(id); // same path as a real approved PayPal payment
            case DECLINED -> {
                handleFailed(payment.getProviderPaymentId(), "Card declined (sandbox)");
                yield PaymentResponse.from(findPayment(id), isOwner(payment));
            }
        };
    }

    /**
     * Internal (order service, while cancelling an order): refunds the order's successful payment.
     * Idempotent: an order already refunded succeeds again without a second refund.
     * Does not call the order service back: the order service is the caller and updates itself.
     */
    public void refundOrder(UUID orderId) {
        tx.executeWithoutResult(status -> {
            List<Payment> payments = paymentRepository.lockAllByOrderId(orderId);
            Optional<Payment> succeeded = payments.stream()
                    .filter(payment -> payment.getStatus() == PaymentStatus.SUCCEEDED)
                    .findFirst();
            if (succeeded.isPresent()) {
                refund(succeeded.get(), null);
                return;
            }
            boolean alreadyRefunded = payments.stream()
                    .anyMatch(payment -> payment.getStatus() == PaymentStatus.REFUNDED && payment.getPaidAt() != null);
            if (!alreadyRefunded) {
                throw new ConflictException("Order %s has no successful payment to refund".formatted(orderId));
            }
        });
    }

    /**
     * Admin: refunds one payment (e.g. for a returned order) and tells the order service.
     * Calling it again on a refunded payment re-sends that notification, which lets an admin retry
     * if the order service was unreachable the first time.
     */
    public PaymentResponse refundPayment(UUID id) {
        UUID orderId = findPayment(id).getOrderId();
        boolean notifyOrder = Boolean.TRUE.equals(tx.execute(status -> {
            List<Payment> payments = paymentRepository.lockAllByOrderId(orderId);
            Payment payment = payments.stream().filter(p -> p.getId().equals(id)).findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("Payment", id));
            switch (payment.getStatus()) {
                case SUCCEEDED -> refund(payment, null);
                case REFUNDED -> { /* already refunded: only re-notify below */ }
                default -> throw new ConflictException("Only a succeeded payment can be refunded");
            }
            // A refunded duplicate must not mark the order as refunded while its real payment still stands.
            return payments.stream().noneMatch(p -> p.getStatus() == PaymentStatus.SUCCEEDED);
        }));
        if (notifyOrder) {
            try {
                orderClient.reportPayment(orderId, "REFUNDED", id);
            } catch (OrderRejectedPaymentException ex) {
                log.info("Order {} did not record refund of payment {}: {}", orderId, id, ex.getMessage());
            }
        }
        return PaymentResponse.from(findPayment(id), false);
    }

    /** Locked part of a success: decides between "succeeded" and "duplicate, refund it". */
    private Payment markSucceeded(String providerPaymentId, String captureId) {
        Optional<Payment> locked = lockPayment(providerPaymentId);
        if (locked.isEmpty()) {
            log.warn("Success notification for unknown provider payment {}", providerPaymentId);
            return null;
        }
        Payment payment = locked.get();
        if (payment.getStatus() == PaymentStatus.SUCCEEDED || payment.getStatus() == PaymentStatus.REFUNDED) {
            return payment; // repeated notification: SUCCEEDED is confirmed to the order again, harmlessly
        }
        payment.setPaidAt(Instant.now());
        payment.setFailureReason(null);
        if (captureId != null) {
            payment.setProviderCaptureId(captureId); // needed for any later refund
        }
        boolean orderAlreadyPaid = paymentRepository.lockAllByOrderId(payment.getOrderId()).stream()
                .anyMatch(other -> !other.getId().equals(payment.getId()) && other.getStatus() == PaymentStatus.SUCCEEDED);
        if (orderAlreadyPaid) {
            log.warn("Duplicate payment {} for already paid order {}: refunding", payment.getId(), payment.getOrderReference());
            refund(payment, "Duplicate payment: the order was already paid");
        } else {
            payment.setStatus(PaymentStatus.SUCCEEDED);
        }
        return payment;
    }

    /** Outside any lock: the order service may itself be waiting on our locks while it cancels. */
    private void confirmToOrder(Payment payment) {
        try {
            orderClient.reportPayment(payment.getOrderId(), "PAID", payment.getId());
        } catch (OrderRejectedPaymentException ex) {
            log.warn("Order {} refused payment {} ({}): refunding", payment.getOrderReference(), payment.getId(),
                    ex.getMessage());
            tx.executeWithoutResult(status -> paymentRepository.lockAllByOrderId(payment.getOrderId()).stream()
                    .filter(p -> p.getId().equals(payment.getId()) && p.getStatus() == PaymentStatus.SUCCEEDED)
                    .findFirst()
                    .ifPresent(p -> refund(p, "The order was cancelled before the payment completed")));
        }
        // ServiceUnavailableException propagates: the provider will redeliver the webhook later, and the
        // repeated notification confirms the payment to the order again.
    }

    private void refund(Payment payment, String reason) {
        gateway.refund(payment); // idempotent at the provider (keyed by payment id)
        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setRefundedAt(Instant.now());
        if (reason != null) {
            payment.setFailureReason(reason);
        }
    }

    /** Loads the payment under the lock of its order's payments. Must run inside a transaction. */
    private Optional<Payment> lockPayment(String providerPaymentId) {
        return paymentRepository.findOrderIdByProviderPaymentId(providerPaymentId)
                .flatMap(orderId -> paymentRepository.lockAllByOrderId(orderId).stream()
                        .filter(payment -> providerPaymentId.equals(payment.getProviderPaymentId()))
                        .findFirst());
    }

    private Payment findPayment(UUID id) {
        return paymentRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Payment", id));
    }

    private static boolean isOwner(Payment payment) {
        return CurrentUser.id().map(id -> Objects.equals(id, payment.getUserId())).orElse(false);
    }

    /** created is false when an open payment for the order was returned instead of a new one. */
    public record CreatedPayment(PaymentResponse payment, boolean created) {
    }
}
