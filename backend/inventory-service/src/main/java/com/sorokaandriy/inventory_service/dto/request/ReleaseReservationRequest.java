package com.sorokaandriy.inventory_service.dto.request;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ReleaseReservationRequest(
        @NotNull(message = "orderId is required")
        UUID orderId
) {
}
