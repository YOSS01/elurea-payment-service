package com.elurea.payment_service.client;

import com.elurea.payment_service.config.AppProperties;
import com.elurea.payment_service.exception.ResourceNotFoundException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Talks to the order service: reads an order on behalf of the customer (their token is forwarded,
 * so the order service enforces ownership), and reports payment outcomes with the internal API key.
 */
@Component
public class OrderServiceClient {

    private static final String SERVICE = "Order service";
    private static final String API_KEY_HEADER = "X-Internal-Api-Key";

    private final RestClient restClient;
    private final String apiKey;

    public OrderServiceClient(AppProperties properties) {
        this.restClient = RestClients.create(properties.services().orderUrl(), properties.services());
        this.apiKey = properties.services().internalApiKey();
    }

    /** 404 if the order does not exist, 403 if it belongs to someone else. */
    public OrderView getOrder(UUID orderId, String bearerToken) {
        try {
            return restClient.get()
                    .uri("/api/orders/{id}", orderId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken)
                    .retrieve()
                    .body(OrderView.class);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
                throw new ResourceNotFoundException("Order", orderId);
            }
            if (ex.getStatusCode().isSameCodeAs(HttpStatus.FORBIDDEN)) {
                throw new AccessDeniedException("This order belongs to another user");
            }
            throw RestClients.unavailable(SERVICE, ex);
        } catch (RestClientException ex) {
            throw RestClients.unavailable(SERVICE, ex);
        }
    }

    /**
     * Tells the order service that the order is PAID or REFUNDED. Idempotent on the order side.
     *
     * @throws OrderRejectedPaymentException when the order refuses the payment (e.g. it was cancelled meanwhile)
     */
    public void reportPayment(UUID orderId, String status, UUID paymentId) {
        try {
            restClient.put()
                    .uri("/internal/orders/{id}/payment-status", orderId)
                    .header(API_KEY_HEADER, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("status", status, "paymentId", paymentId))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)) {
                throw new OrderRejectedPaymentException(RestClients.detailOf(ex));
            }
            throw RestClients.unavailable(SERVICE, ex);
        } catch (RestClientException ex) {
            throw RestClients.unavailable(SERVICE, ex);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OrderView(UUID id, String reference, UUID userId, String status, String paymentMethod,
                            String paymentStatus, BigDecimal totalPrice) {
    }

    /** The order cannot accept this payment, typically because it was cancelled before the payment completed. */
    public static class OrderRejectedPaymentException extends RuntimeException {
        public OrderRejectedPaymentException(String message) {
            super(message);
        }
    }
}
