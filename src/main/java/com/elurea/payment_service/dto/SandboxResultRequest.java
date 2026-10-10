package com.elurea.payment_service.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Sandbox only: what the simulated customer did at the "bank".
 */
public record SandboxResultRequest(
        @NotNull
        Outcome outcome
) {

    public enum Outcome {
        SUCCEEDED,
        DECLINED
    }
}
