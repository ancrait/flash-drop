package com.sorokaandriy.inventory_service.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record ReservationItemRequest(
        @NotNull(message = "productId is required")
        UUID productId,

        @NotNull(message = "quantity is required")
        @Positive(message = "quantity must be greater than zero")
        Long quantity,

        @NotNull(message = "expectedPriceMinor is required")
        @Positive(message = "expectedPriceMinor must be greater than zero")
        Long expectedPriceMinor
) {
}
