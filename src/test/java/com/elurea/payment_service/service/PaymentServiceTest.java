package com.elurea.payment_service.service;

import com.elurea.payment_service.client.OrderServiceClient;
import com.elurea.payment_service.client.OrderServiceClient.OrderRejectedPaymentException;
import com.elurea.payment_service.client.OrderServiceClient.OrderView;
import com.elurea.payment_service.config.AppProperties;
import com.elurea.payment_service.entity.Payment;
import com.elurea.payment_service.enums.PaymentProvider;
import com.elurea.payment_service.enums.PaymentStatus;
import com.elurea.payment_service.exception.ConflictException;
import com.elurea.payment_service.gateway.PaymentGateway;
import com.elurea.payment_service.repository.PaymentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentServiceTest {

    private final UUID customerId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    private PaymentRepository repository;
    private PaymentGateway gateway;
    private OrderServiceClient orderClient;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentRepository.class);
        gateway = mock(PaymentGateway.class);
        orderClient = mock(OrderServiceClient.class);
        when(gateway.provider()).thenReturn(PaymentProvider.SANDBOX);
        when(repository.saveAndFlush(any(Payment.class))).thenAnswer(invocation -> {
            Payment payment = invocation.getArgument(0);
            if (payment.getId() == null) {
                ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
            }
            return payment;
        });
        var properties = new AppProperties(null, null,
                new AppProperties.Services("http://order", "key", Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new AppProperties.Payments("sandbox", "mad", null));
        service = new PaymentService(repository, gateway, orderClient,
                new TransactionTemplate(mock(PlatformTransactionManager.class)), properties);

        Jwt jwt = Jwt.withTokenValue("token").header("alg", "HS256").subject(customerId.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_USER")));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void paymentAmountComesFromTheOrderService() {
        when(orderClient.getOrder(eq(orderId), any())).thenReturn(order("ONLINE", "UNPAID", "PENDING", "169.20"));
        when(gateway.create(any())).thenReturn(
                new PaymentGateway.ProviderPayment("PP-1", "https://paypal/approve/PP-1", new BigDecimal("15.57"), "EUR"));

        var result = service.create(orderId);

        assertThat(result.created()).isTrue();
        assertThat(result.payment().amount()).isEqualByComparingTo("169.20");
        assertThat(result.payment().currency()).isEqualTo("MAD");
        assertThat(result.payment().chargedAmount()).isEqualByComparingTo("15.57");
        assertThat(result.payment().chargedCurrency()).isEqualTo("EUR");
        assertThat(result.payment().providerPaymentId()).isEqualTo("PP-1");
        assertThat(result.payment().approvalUrl()).isEqualTo("https://paypal/approve/PP-1");
        assertThat(result.payment().status()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void openPaymentIsReusedInsteadOfOpeningAnother() {
        when(orderClient.getOrder(eq(orderId), any())).thenReturn(order("ONLINE", "UNPAID", "PENDING", "50.00"));
        Payment open = payment(PaymentStatus.PENDING, "sbx_open");
        when(repository.findFirstByOrderIdAndStatusOrderByCreatedAtDesc(orderId, PaymentStatus.PENDING))
                .thenReturn(Optional.of(open));

        var result = service.create(orderId);

        assertThat(result.created()).isFalse();
        assertThat(result.payment().id()).isEqualTo(open.getId());
        verify(gateway, never()).create(any());
    }

    @Test
    void ordersThatCannotBePaidOnlineAreRejected() {
        when(orderClient.getOrder(eq(orderId), any()))
                .thenReturn(order("CASH_ON_DELIVERY", "UNPAID", "PENDING", "10.00"))
                .thenReturn(order("ONLINE", "PAID", "CONFIRMED", "10.00"))
                .thenReturn(order("ONLINE", "UNPAID", "CANCELLED", "10.00"));

        assertThatThrownBy(() -> service.create(orderId)).isInstanceOf(ConflictException.class).hasMessageContaining("on delivery");
        assertThatThrownBy(() -> service.create(orderId)).isInstanceOf(ConflictException.class).hasMessageContaining("already PAID");
        assertThatThrownBy(() -> service.create(orderId)).isInstanceOf(ConflictException.class).hasMessageContaining("CANCELLED");
        verify(gateway, never()).create(any());
    }

    @Test
    void successfulPaymentIsRecordedAndConfirmedToTheOrder() {
        Payment payment = payment(PaymentStatus.PENDING, "sbx_1");
        lockable(payment);

        service.handleSucceeded("sbx_1", "CAP-1");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getPaidAt()).isNotNull();
        verify(orderClient).reportPayment(orderId, "PAID", payment.getId());
        verify(gateway, never()).refund(any());
    }

    @Test
    void repeatedWebhookIsHarmless() {
        Payment payment = payment(PaymentStatus.PENDING, "sbx_1");
        lockable(payment);

        service.handleSucceeded("sbx_1", "CAP-1");
        service.handleSucceeded("sbx_1", "CAP-1");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        verify(orderClient, times(2)).reportPayment(orderId, "PAID", payment.getId()); // idempotent on the order side
        verify(gateway, never()).refund(any());
    }

    @Test
    void secondSuccessfulPaymentForTheSameOrderIsRefunded() {
        Payment first = payment(PaymentStatus.SUCCEEDED, "sbx_1");
        Payment duplicate = payment(PaymentStatus.PENDING, "sbx_2");
        lockable(first, duplicate);

        service.handleSucceeded("sbx_2", "CAP-2");

        assertThat(duplicate.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(duplicate.getFailureReason()).contains("Duplicate");
        verify(gateway).refund(duplicate);
        verify(orderClient, never()).reportPayment(any(), anyString(), any());
    }

    @Test
    void paymentCompletingAfterTheOrderWasCancelledIsRefunded() {
        Payment payment = payment(PaymentStatus.PENDING, "sbx_1");
        lockable(payment);
        doThrow(new OrderRejectedPaymentException("Order is CANCELLED"))
                .when(orderClient).reportPayment(orderId, "PAID", payment.getId());

        service.handleSucceeded("sbx_1", "CAP-1");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(gateway).refund(payment);
    }

    @Test
    void approvedPaymentIsCapturedServerSideAndCaptureIdKept() {
        Payment payment = payment(PaymentStatus.PENDING, "PP-1");
        lockable(payment);
        when(repository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(gateway.capture(payment)).thenReturn(
                new PaymentGateway.CaptureResult(PaymentGateway.CaptureResult.Outcome.COMPLETED, "CAP-1", null));

        var response = service.capture(payment.getId());

        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(payment.getProviderCaptureId()).isEqualTo("CAP-1");
        verify(orderClient).reportPayment(orderId, "PAID", payment.getId());
    }

    @Test
    void declinedCaptureMarksThePaymentFailed() {
        Payment payment = payment(PaymentStatus.PENDING, "PP-1");
        lockable(payment);
        when(repository.findById(payment.getId())).thenReturn(Optional.of(payment));
        when(gateway.capture(payment)).thenReturn(new PaymentGateway.CaptureResult(
                PaymentGateway.CaptureResult.Outcome.DECLINED, null, "The instrument presented was declined."));

        var response = service.capture(payment.getId());

        assertThat(response.status()).isEqualTo(PaymentStatus.FAILED);
        verify(orderClient, never()).reportPayment(any(), anyString(), any());
    }

    @Test
    void capturingAnAlreadyPaidPaymentDoesNotCallPayPalAgain() {
        Payment payment = payment(PaymentStatus.SUCCEEDED, "PP-1");
        when(repository.findById(payment.getId())).thenReturn(Optional.of(payment));

        var response = service.capture(payment.getId());

        assertThat(response.status()).isEqualTo(PaymentStatus.SUCCEEDED);
        verify(gateway, never()).capture(any());
    }

    @Test
    void declinedPaymentIsMarkedFailedAndCanBeRetried() {
        Payment payment = payment(PaymentStatus.PENDING, "sbx_1");
        lockable(payment);

        service.handleFailed("sbx_1", "Card declined");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("Card declined");
    }

    @Test
    void refundForCancelledOrderIsIdempotent() {
        Payment payment = payment(PaymentStatus.SUCCEEDED, "sbx_1");
        payment.setPaidAt(java.time.Instant.now());
        lockable(payment);

        service.refundOrder(orderId);
        service.refundOrder(orderId);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verify(gateway, times(1)).refund(payment);
    }

    @Test
    void refundForOrderWithoutSuccessfulPaymentIsRefused() {
        lockable(payment(PaymentStatus.FAILED, "sbx_1"));

        assertThatThrownBy(() -> service.refundOrder(orderId)).isInstanceOf(ConflictException.class);
        verify(gateway, never()).refund(any());
    }

    private void lockable(Payment... payments) {
        for (Payment payment : payments) {
            when(repository.findOrderIdByProviderPaymentId(payment.getProviderPaymentId())).thenReturn(Optional.of(orderId));
        }
        when(repository.lockAllByOrderId(orderId)).thenReturn(List.of(payments));
    }

    private Payment payment(PaymentStatus status, String providerPaymentId) {
        Payment payment = new Payment();
        ReflectionTestUtils.setField(payment, "id", UUID.randomUUID());
        payment.setOrderId(orderId);
        payment.setOrderReference("EL261010-ABCDEF");
        payment.setUserId(customerId);
        payment.setAmount(new BigDecimal("50.00"));
        payment.setCurrency("MAD");
        payment.setProvider(PaymentProvider.SANDBOX);
        payment.setProviderPaymentId(providerPaymentId);
        payment.setStatus(status);
        return payment;
    }

    private OrderView order(String method, String paymentStatus, String status, String total) {
        return new OrderView(orderId, "EL261010-ABCDEF", customerId, status, method, paymentStatus, new BigDecimal(total));
    }
}
