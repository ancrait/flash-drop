package com.sorokaandriy.inventory_service.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AdjustStockRequest(
        @NotNull(message = "quantity is required")
        @Positive(message = "quantity must be greater than zero")
        Long quantity
) {
}
