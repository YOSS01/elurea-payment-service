package com.elurea.payment_service.gateway;

import com.elurea.payment_service.config.AppProperties;
import com.elurea.payment_service.entity.Payment;
import com.elurea.payment_service.exception.BadRequestException;
import com.elurea.payment_service.exception.ConflictException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class PayPalPaymentGatewayTest {

    private static final String API = "https://api-m.sandbox.paypal.com";
    private static final Instant NOW = Instant.parse("2026-10-10T12:00:00Z");

    private MockRestServiceServer paypal;
    private PayPalPaymentGateway gateway;
    private Payment payment;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        paypal = MockRestServiceServer.bindTo(builder).build();
        gateway = new PayPalPaymentGateway(properties("EUR", new BigDecimal("0.092")), JsonMapper.builder().build(),
                builder, Clock.fixed(NOW, ZoneOffset.UTC));

        payment = new Payment();
        ReflectionTestUtils.setField(payment, "id", UUID.fromString("11111111-2222-3333-4444-555555555555"));
        payment.setOrderId(UUID.randomUUID());
        payment.setOrderReference("EL261010-ABCDEF");
        payment.setAmount(new BigDecimal("169.20"));
        payment.setCurrency("MAD");
        payment.setProviderPaymentId("PP-ORDER-1");
    }

    @Test
    void createsPayPalOrderInEurosWithIdempotencyKey() {
        expectToken();
        paypal.expect(requestTo(API + "/v2/checkout/orders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer TOKEN-1"))
                .andExpect(header("PayPal-Request-Id", "create-11111111-2222-3333-4444-555555555555"))
                .andExpect(jsonPath("$.intent").value("CAPTURE"))
                .andExpect(jsonPath("$.purchase_units[0].amount.currency_code").value("EUR"))
                .andExpect(jsonPath("$.purchase_units[0].amount.value").value("15.57")) // 169.20 x 0.092 = 15.5664
                .andExpect(jsonPath("$.purchase_units[0].custom_id").value("11111111-2222-3333-4444-555555555555"))
                .andRespond(withSuccess("""
                        {"id": "PP-ORDER-1", "status": "PAYER_ACTION_REQUIRED",
                         "links": [{"rel": "self", "href": "https://api/self"},
                                   {"rel": "payer-action", "href": "https://www.sandbox.paypal.com/checkoutnow?token=PP-ORDER-1"}]}
                        """, MediaType.APPLICATION_JSON));

        PaymentGateway.ProviderPayment created = gateway.create(payment);

        assertThat(created.providerPaymentId()).isEqualTo("PP-ORDER-1");
        assertThat(created.approvalUrl()).endsWith("token=PP-ORDER-1");
        assertThat(created.chargedAmount()).isEqualByComparingTo("15.57");
        assertThat(created.chargedCurrency()).isEqualTo("EUR");
        paypal.verify();
    }

    @Test
    void accessTokenIsReusedUntilItExpires() {
        expectToken();
        expectCapture(withSuccess(completedOrder("CAP-1"), MediaType.APPLICATION_JSON));
        paypal.expect(requestTo(API + "/v2/payments/captures/CAP-1/refund"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer TOKEN-1"))
                .andRespond(withSuccess("{\"status\":\"COMPLETED\"}", MediaType.APPLICATION_JSON));

        gateway.capture(payment);
        payment.setProviderCaptureId("CAP-1");
        gateway.refund(payment);

        paypal.verify(); // only one token request for both calls
    }

    @Test
    void completedCaptureReturnsTheCaptureId() {
        expectToken();
        expectCapture(withSuccess(completedOrder("CAP-9"), MediaType.APPLICATION_JSON));

        PaymentGateway.CaptureResult result = gateway.capture(payment);

        assertThat(result.outcome()).isEqualTo(PaymentGateway.CaptureResult.Outcome.COMPLETED);
        assertThat(result.captureId()).isEqualTo("CAP-9");
    }

    @Test
    void declinedCardIsReportedAsDeclined() {
        expectToken();
        expectCapture(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON).body("""
                {"name": "UNPROCESSABLE_ENTITY",
                 "details": [{"issue": "INSTRUMENT_DECLINED", "description": "The instrument presented was declined."}]}
                """));

        PaymentGateway.CaptureResult result = gateway.capture(payment);

        assertThat(result.outcome()).isEqualTo(PaymentGateway.CaptureResult.Outcome.DECLINED);
        assertThat(result.failureReason()).contains("declined");
    }

    @Test
    void alreadyCapturedOrderIsReadInsteadOfFailing() {
        expectToken();
        expectCapture(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
                .body("{\"details\":[{\"issue\":\"ORDER_ALREADY_CAPTURED\",\"description\":\"already\"}]}"));
        paypal.expect(requestTo(API + "/v2/checkout/orders/PP-ORDER-1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(completedOrder("CAP-7"), MediaType.APPLICATION_JSON));

        PaymentGateway.CaptureResult result = gateway.capture(payment);

        assertThat(result.outcome()).isEqualTo(PaymentGateway.CaptureResult.Outcome.COMPLETED);
        assertThat(result.captureId()).isEqualTo("CAP-7");
    }

    @Test
    void captureBeforeBuyerApprovalIsRefused() {
        expectToken();
        expectCapture(withStatus(HttpStatus.UNPROCESSABLE_ENTITY).contentType(MediaType.APPLICATION_JSON)
                .body("{\"details\":[{\"issue\":\"ORDER_NOT_APPROVED\",\"description\":\"not approved\"}]}"));

        assertThatThrownBy(() -> gateway.capture(payment)).isInstanceOf(ConflictException.class);
    }

    @Test
    void verifiedWebhookIsParsed() {
        String event = captureEvent("PAYMENT.CAPTURE.COMPLETED");
        expectToken();
        paypal.expect(requestTo(API + "/v1/notifications/verify-webhook-signature"))
                .andExpect(jsonPath("$.webhook_id").value("WH-1"))
                .andExpect(jsonPath("$.transmission_id").value("tx-1"))
                .andExpect(content().string(containsString("\"webhook_event\":" + event)))
                .andRespond(withSuccess("{\"verification_status\":\"SUCCESS\"}", MediaType.APPLICATION_JSON));

        WebhookEvent parsed = gateway.parseWebhook(event, webhookHeaders(NOW));

        assertThat(parsed.kind()).isEqualTo(WebhookEvent.Kind.SUCCEEDED);
        assertThat(parsed.providerPaymentId()).isEqualTo("PP-ORDER-1");
        assertThat(parsed.captureId()).isEqualTo("CAP-5");
    }

    @Test
    void forgedWebhookIsRejected() {
        expectToken();
        paypal.expect(requestTo(API + "/v1/notifications/verify-webhook-signature"))
                .andRespond(withSuccess("{\"verification_status\":\"FAILURE\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> gateway.parseWebhook(captureEvent("PAYMENT.CAPTURE.COMPLETED"), webhookHeaders(NOW)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void staleOrIncompleteWebhookIsRejectedWithoutCallingPayPal() {
        String event = captureEvent("PAYMENT.CAPTURE.COMPLETED");

        assertThatThrownBy(() -> gateway.parseWebhook(event, webhookHeaders(NOW.minus(Duration.ofHours(1)))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("tolerance");
        assertThatThrownBy(() -> gateway.parseWebhook(event, new HttpHeaders()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Missing webhook header");
        paypal.verify(); // no request was made
    }

    @Test
    void refusesToStartWithoutExchangeRateWhenCurrenciesDiffer() {
        assertThatThrownBy(() -> new PayPalPaymentGateway(properties("EUR", null), JsonMapper.builder().build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAYPAL_EXCHANGE_RATE");
    }

    @Test
    void refusesToStartWithoutCredentials() {
        var config = new AppProperties.PayPal("", "", "", API, "EUR", BigDecimal.ONE, "Elurea", "r", "c", Duration.ofMinutes(10));
        var properties = new AppProperties(null, null, services(), new AppProperties.Payments("paypal", "MAD", config));

        assertThatThrownBy(() -> new PayPalPaymentGateway(properties, JsonMapper.builder().build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAYPAL_CLIENT_ID");
    }

    private void expectToken() {
        paypal.expect(requestTo(API + "/v1/oauth2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header(HttpHeaders.AUTHORIZATION, startsWith("Basic ")))
                .andExpect(content().string("grant_type=client_credentials"))
                .andRespond(withSuccess("{\"access_token\":\"TOKEN-1\",\"expires_in\":32400}", MediaType.APPLICATION_JSON));
    }

    private void expectCapture(org.springframework.test.web.client.ResponseCreator response) {
        paypal.expect(requestTo(API + "/v2/checkout/orders/PP-ORDER-1/capture"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("PayPal-Request-Id", "capture-11111111-2222-3333-4444-555555555555"))
                .andRespond(response);
    }

    private static String completedOrder(String captureId) {
        return """
                {"id": "PP-ORDER-1", "status": "COMPLETED",
                 "purchase_units": [{"payments": {"captures": [{"id": "%s", "status": "COMPLETED"}]}}]}
                """.formatted(captureId);
    }

    private static String captureEvent(String type) {
        return "{\"id\":\"WH-EVT-1\",\"event_type\":\"" + type + "\",\"resource\":{\"id\":\"CAP-5\",\"status\":\"COMPLETED\","
                + "\"supplementary_data\":{\"related_ids\":{\"order_id\":\"PP-ORDER-1\"}}}}";
    }

    private static HttpHeaders webhookHeaders(Instant sentAt) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("PAYPAL-TRANSMISSION-ID", "tx-1");
        headers.add("PAYPAL-TRANSMISSION-TIME", sentAt.toString());
        headers.add("PAYPAL-TRANSMISSION-SIG", "sig");
        headers.add("PAYPAL-CERT-URL", "https://api.paypal.com/cert.pem");
        headers.add("PAYPAL-AUTH-ALGO", "SHA256withRSA");
        return headers;
    }

    private static AppProperties properties(String paypalCurrency, BigDecimal rate) {
        var config = new AppProperties.PayPal("client", "secret", "WH-1", API, paypalCurrency, rate, "Elurea",
                "http://localhost:3000/return", "http://localhost:3000/cancel", Duration.ofMinutes(10));
        return new AppProperties(null, null, services(), new AppProperties.Payments("paypal", "MAD", config));
    }

    private static AppProperties.Services services() {
        return new AppProperties.Services("http://localhost:8083", "key", Duration.ofSeconds(2), Duration.ofSeconds(5));
    }
}
