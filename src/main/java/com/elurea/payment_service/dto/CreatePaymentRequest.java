package com.elurea.payment_service.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Only the order is given: the amount is always read from the order service.
 */
public record CreatePaymentRequest(
        @NotNull
        UUID orderId
) {
}
