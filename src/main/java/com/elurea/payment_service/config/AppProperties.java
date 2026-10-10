package com.elurea.payment_service.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * Typed view of the "app.*" settings in application.properties.
 */
@ConfigurationProperties("app")
public record AppProperties(
        @DefaultValue Jwt jwt,
        @DefaultValue Cors cors,
        @DefaultValue Services services,
        @DefaultValue Payments payments
) {

    /** Must match the user service, which signs the tokens. */
    public record Jwt(String secret, @DefaultValue("elurea-user-service") String issuer) {
    }

    public record Cors(@DefaultValue("http://localhost:3000") List<String> allowedOrigins) {
    }

    public record Services(
            @DefaultValue("http://localhost:8083") String orderUrl,
            String internalApiKey,
            @DefaultValue("2s") Duration connectTimeout,
            @DefaultValue("5s") Duration readTimeout
    ) {
    }

    /**
     * @param provider "sandbox" (simulated) or "paypal"
     * @param currency currency of the shop's prices, i.e. of the orders (e.g. MAD)
     */
    public record Payments(
            @DefaultValue("sandbox") String provider,
            @DefaultValue("MAD") String currency,
            @DefaultValue PayPal paypal
    ) {
    }

    /**
     * PayPal REST API (Orders v2).
     *
     * @param apiUrl       https://api-m.sandbox.paypal.com for testing, https://api-m.paypal.com for live payments
     * @param webhookId    id of the webhook created in the PayPal developer dashboard (used to verify notifications)
     * @param currency     currency PayPal charges in. PayPal does not support MAD, so this is typically EUR or USD
     * @param exchangeRate value of 1 unit of the shop currency in the PayPal currency (e.g. 1 MAD = 0.092 EUR).
     *                     Required when the two currencies differ
     */
    public record PayPal(
            String clientId,
            String clientSecret,
            String webhookId,
            @DefaultValue("https://api-m.sandbox.paypal.com") String apiUrl,
            @DefaultValue("EUR") String currency,
            BigDecimal exchangeRate,
            @DefaultValue("Elurea") String brandName,
            @DefaultValue("http://localhost:3000/checkout/paypal/return") String returnUrl,
            @DefaultValue("http://localhost:3000/checkout/paypal/cancel") String cancelUrl,
            /* Notifications older than this are rejected, to defeat replays of captured requests. */
            @DefaultValue("10m") Duration webhookTolerance
    ) {
    }
}
