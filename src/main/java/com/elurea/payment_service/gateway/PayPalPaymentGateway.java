package com.elurea.payment_service.gateway;

import com.elurea.payment_service.config.AppProperties;
import com.elurea.payment_service.entity.Payment;
import com.elurea.payment_service.enums.PaymentProvider;
import com.elurea.payment_service.exception.BadRequestException;
import com.elurea.payment_service.exception.ConflictException;
import com.elurea.payment_service.exception.ServiceUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PayPal Checkout through the REST API (Orders v2), without SDK.
 * <p>
 * Flow: we create a PayPal order and give its id to the browser; the buyer approves it in the PayPal popup
 * (JavaScript SDK) or on the approval page; the frontend then asks us to capture it, and we capture it
 * server-side with PayPal. Webhooks cover captures that complete or fail later. Nothing the browser
 * reports is trusted: only PayPal's answers change a payment's status.
 * <p>
 * PayPal does not support MAD, so amounts are converted to the configured PayPal currency (EUR by default)
 * with the configured exchange rate.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.payments.provider", havingValue = "paypal")
public class PayPalPaymentGateway implements PaymentGateway {

    /** Refresh the OAuth token a little before PayPal expires it. */
    private static final Duration TOKEN_SAFETY_MARGIN = Duration.ofSeconds(60);

    private final RestClient restClient;
    private final JsonMapper jsonMapper;
    private final Clock clock;
    private final AppProperties.PayPal config;
    private final String shopCurrency;
    private final String chargeCurrency;
    private final BigDecimal exchangeRate;

    private String accessToken;
    private Instant accessTokenExpiresAt = Instant.EPOCH;

    @Autowired
    public PayPalPaymentGateway(AppProperties properties, JsonMapper jsonMapper) {
        this(properties, jsonMapper, RestClient.builder().requestFactory(requestFactory(properties)), Clock.systemUTC());
    }

    /** For tests: lets a mock server stand in for PayPal and fixes the clock. */
    PayPalPaymentGateway(AppProperties properties, JsonMapper jsonMapper, RestClient.Builder restClientBuilder, Clock clock) {
        this.config = properties.payments().paypal();
        this.shopCurrency = properties.payments().currency().toUpperCase(Locale.ROOT);
        this.chargeCurrency = config.currency().toUpperCase(Locale.ROOT);
        requireSetting(config.clientId(), "PAYPAL_CLIENT_ID");
        requireSetting(config.clientSecret(), "PAYPAL_CLIENT_SECRET");
        requireSetting(config.webhookId(), "PAYPAL_WEBHOOK_ID");
        if (shopCurrency.equals(chargeCurrency)) {
            this.exchangeRate = BigDecimal.ONE;
        } else {
            if (config.exchangeRate() == null || config.exchangeRate().signum() <= 0) {
                throw new IllegalStateException("PayPal charges in %s but prices are in %s: set PAYPAL_EXCHANGE_RATE (value of 1 %s in %s)"
                        .formatted(chargeCurrency, shopCurrency, shopCurrency, chargeCurrency));
            }
            this.exchangeRate = config.exchangeRate();
        }
        this.restClient = restClientBuilder.baseUrl(config.apiUrl()).build();
        this.jsonMapper = jsonMapper;
        this.clock = clock;
        log.info("PayPal payments enabled ({}), charging in {} at 1 {} = {} {}",
                config.apiUrl(), chargeCurrency, shopCurrency, exchangeRate, chargeCurrency);
    }

    @Override
    public PaymentProvider provider() {
        return PaymentProvider.PAYPAL;
    }

    @Override
    public ProviderPayment create(Payment payment) {
        BigDecimal charged = toChargeAmount(payment.getAmount());
        Map<String, Object> body = Map.of(
                "intent", "CAPTURE",
                "purchase_units", List.of(Map.of(
                        "reference_id", payment.getOrderReference(),
                        "custom_id", payment.getId().toString(),
                        "invoice_id", payment.getId().toString(),
                        "description", "Order " + payment.getOrderReference(),
                        "amount", Map.of("currency_code", chargeCurrency, "value", charged.toPlainString()))),
                "payment_source", Map.of("paypal", Map.of("experience_context", Map.of(
                        "brand_name", config.brandName(),
                        "user_action", "PAY_NOW",
                        "shipping_preference", "NO_SHIPPING",
                        "return_url", config.returnUrl(),
                        "cancel_url", config.cancelUrl()))));

        JsonNode order = send(HttpMethod.POST, "/v2/checkout/orders", toJson(body), "create-" + payment.getId());
        return new ProviderPayment(order.path("id").asString(), approvalLink(order), charged, chargeCurrency);
    }

    @Override
    public CaptureResult capture(Payment payment) {
        String path = "/v2/checkout/orders/" + payment.getProviderPaymentId() + "/capture";
        try {
            return fromOrder(send(HttpMethod.POST, path, "{}", "capture-" + payment.getId()));
        } catch (PayPalErrorException ex) {
            return switch (ex.issue) {
                // A previous capture call succeeded but its answer was lost: read the result instead.
                case "ORDER_ALREADY_CAPTURED" -> fromOrder(
                        send(HttpMethod.GET, "/v2/checkout/orders/" + payment.getProviderPaymentId(), null, null));
                case "ORDER_NOT_APPROVED", "PAYER_ACTION_REQUIRED" ->
                        throw new ConflictException("The buyer has not approved this payment on PayPal yet");
                default -> new CaptureResult(CaptureResult.Outcome.DECLINED, null, ex.description);
            };
        }
    }

    @Override
    public void refund(Payment payment) {
        if (payment.getProviderCaptureId() == null) {
            throw new IllegalStateException("Payment " + payment.getId() + " has no PayPal capture to refund");
        }
        try {
            send(HttpMethod.POST, "/v2/payments/captures/" + payment.getProviderCaptureId() + "/refund", "{}",
                    "refund-" + payment.getId());
        } catch (PayPalErrorException ex) {
            if (!"CAPTURE_FULLY_REFUNDED".equals(ex.issue)) {
                throw new ServiceUnavailableException("PayPal refused the refund: " + ex.issue + " " + ex.description, ex);
            }
            // Already refunded (e.g. a retried request): nothing left to do.
        }
    }

    /**
     * Verifies a webhook with PayPal (verify-webhook-signature API) and extracts the outcome.
     * Unverified, malformed or stale notifications are rejected with 400.
     */
    public WebhookEvent parseWebhook(String payload, HttpHeaders headers) {
        String transmissionId = requireHeader(headers, "PAYPAL-TRANSMISSION-ID");
        String transmissionTime = requireHeader(headers, "PAYPAL-TRANSMISSION-TIME");
        String transmissionSig = requireHeader(headers, "PAYPAL-TRANSMISSION-SIG");
        String certUrl = requireHeader(headers, "PAYPAL-CERT-URL");
        String authAlgo = requireHeader(headers, "PAYPAL-AUTH-ALGO");
        checkFreshness(transmissionTime);

        JsonNode event;
        try {
            event = jsonMapper.readTree(payload);
        } catch (JacksonException e) {
            throw new BadRequestException("Malformed webhook payload");
        }

        Map<String, String> verification = new LinkedHashMap<>();
        verification.put("auth_algo", authAlgo);
        verification.put("cert_url", certUrl);
        verification.put("transmission_id", transmissionId);
        verification.put("transmission_sig", transmissionSig);
        verification.put("transmission_time", transmissionTime);
        verification.put("webhook_id", config.webhookId());
        // The event must be sent back byte for byte as received, so it is appended raw (it was parsed above).
        String fields = toJson(verification);
        String verifyBody = fields.substring(0, fields.length() - 1) + ",\"webhook_event\":" + payload + "}";

        JsonNode result;
        try {
            result = send(HttpMethod.POST, "/v1/notifications/verify-webhook-signature", verifyBody, null);
        } catch (PayPalErrorException ex) {
            throw new BadRequestException("Invalid webhook signature");
        }
        if (!"SUCCESS".equals(result.path("verification_status").asString())) {
            throw new BadRequestException("Invalid webhook signature");
        }

        JsonNode resource = event.path("resource");
        String paypalOrderId = resource.path("supplementary_data").path("related_ids").path("order_id").asString();
        return switch (event.path("event_type").asString()) {
            case "PAYMENT.CAPTURE.COMPLETED" ->
                    new WebhookEvent(WebhookEvent.Kind.SUCCEEDED, paypalOrderId, resource.path("id").asString(), null);
            case "PAYMENT.CAPTURE.DENIED", "PAYMENT.CAPTURE.DECLINED" ->
                    new WebhookEvent(WebhookEvent.Kind.FAILED, paypalOrderId, null, "Payment denied by PayPal");
            default -> new WebhookEvent(WebhookEvent.Kind.IGNORED, paypalOrderId, null, null);
        };
    }

    /** Shop amount converted to the PayPal currency, rounded to the cent. */
    BigDecimal toChargeAmount(BigDecimal shopAmount) {
        BigDecimal charged = shopAmount.multiply(exchangeRate).setScale(2, RoundingMode.HALF_UP);
        if (charged.signum() <= 0) {
            throw new BadRequestException("The order amount is too small to be paid with PayPal");
        }
        return charged;
    }

    private CaptureResult fromOrder(JsonNode order) {
        JsonNode capture = order.path("purchase_units").path(0).path("payments").path("captures").path(0);
        String captureId = capture.path("id").asString(null);
        return switch (capture.path("status").asString("")) {
            case "COMPLETED" -> new CaptureResult(CaptureResult.Outcome.COMPLETED, captureId, null);
            case "PENDING" -> new CaptureResult(CaptureResult.Outcome.PENDING, captureId, null);
            default -> new CaptureResult(CaptureResult.Outcome.DECLINED, captureId,
                    "Payment refused by PayPal (" + capture.path("status_details").path("reason").asString("no reason given") + ")");
        };
    }

    private static String approvalLink(JsonNode order) {
        for (JsonNode link : order.path("links")) {
            String rel = link.path("rel").asString("");
            if (rel.equals("payer-action") || rel.equals("approve")) {
                return link.path("href").asString();
            }
        }
        return null;
    }

    private JsonNode send(HttpMethod method, String path, String jsonBody, String requestId) {
        try {
            RestClient.RequestBodySpec request = restClient.method(method)
                    .uri(path)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken());
            if (requestId != null) {
                request.header("PayPal-Request-Id", requestId); // PayPal's idempotency key
            }
            if (jsonBody != null) {
                request.contentType(MediaType.APPLICATION_JSON).body(jsonBody);
            }
            String response = request.retrieve().body(String.class);
            return jsonMapper.readTree(response == null || response.isBlank() ? "{}" : response);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().isSameCodeAs(HttpStatus.UNPROCESSABLE_ENTITY)
                    || ex.getStatusCode().isSameCodeAs(HttpStatus.BAD_REQUEST)
                    || ex.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                throw PayPalErrorException.from(ex, jsonMapper);
            }
            if (ex.getStatusCode().isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
                accessToken = null; // force a new token next time
            }
            throw new ServiceUnavailableException("PayPal " + method + " " + path + " failed with " + ex.getStatusCode(), ex);
        } catch (RestClientException | JacksonException ex) {
            throw new ServiceUnavailableException("PayPal " + method + " " + path + " failed: " + ex.getMessage(), ex);
        }
    }

    /** OAuth 2 client-credentials token, cached until shortly before it expires. */
    private synchronized String accessToken() {
        if (accessToken != null && clock.instant().isBefore(accessTokenExpiresAt)) {
            return accessToken;
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        try {
            String response = restClient.post()
                    .uri("/v1/oauth2/token")
                    .headers(headers -> headers.setBasicAuth(config.clientId(), config.clientSecret()))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);
            JsonNode token = jsonMapper.readTree(response);
            accessToken = token.path("access_token").asString();
            accessTokenExpiresAt = clock.instant()
                    .plusSeconds(token.path("expires_in").asLong(0))
                    .minus(TOKEN_SAFETY_MARGIN);
            return accessToken;
        } catch (RestClientException | JacksonException ex) {
            throw new ServiceUnavailableException("PayPal authentication failed (check PAYPAL_CLIENT_ID/SECRET): "
                    + ex.getMessage(), ex);
        }
    }

    private void checkFreshness(String transmissionTime) {
        try {
            Duration age = Duration.between(Instant.parse(transmissionTime), clock.instant()).abs();
            if (age.compareTo(config.webhookTolerance()) > 0) {
                throw new BadRequestException("Webhook timestamp outside the tolerance window");
            }
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Invalid webhook transmission time");
        }
    }

    private String toJson(Object value) {
        return jsonMapper.writeValueAsString(value);
    }

    private static String requireHeader(HttpHeaders headers, String name) {
        String value = headers.getFirst(name);
        if (value == null || value.isBlank()) {
            throw new BadRequestException("Missing webhook header " + name);
        }
        return value;
    }

    private static void requireSetting(String value, String envName) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("PayPal is selected: " + envName + " must be set");
        }
    }

    private static JdkClientHttpRequestFactory requestFactory(AppProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.services().connectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(30)); // captures can take a while
        return factory;
    }

    /** A business error answered by PayPal (4xx), e.g. INSTRUMENT_DECLINED or ORDER_ALREADY_CAPTURED. */
    static final class PayPalErrorException extends RuntimeException {

        final String issue;
        final String description;

        private PayPalErrorException(String issue, String description, Throwable cause) {
            super(issue + ": " + description, cause);
            this.issue = issue;
            this.description = description;
        }

        static PayPalErrorException from(RestClientResponseException ex, JsonMapper jsonMapper) {
            try {
                JsonNode body = jsonMapper.readTree(ex.getResponseBodyAsString());
                JsonNode detail = body.path("details").path(0);
                return new PayPalErrorException(
                        detail.path("issue").asString(body.path("name").asString("UNKNOWN")),
                        detail.path("description").asString(body.path("message").asString("Payment refused by PayPal")),
                        ex);
            } catch (JacksonException parseError) {
                return new PayPalErrorException("UNKNOWN", "Payment refused by PayPal", ex);
            }
        }
    }
}
