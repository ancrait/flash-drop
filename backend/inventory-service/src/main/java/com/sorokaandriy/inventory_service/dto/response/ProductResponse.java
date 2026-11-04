package com.sorokaandriy.inventory_service.dto.response;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record ProductResponse(
        UUID id,
        String sku,
        String name,
        String description,
        Long priceMinor,
        String currency,
        boolean active,
        Instant createdAt,
        Instant updatedAt,
        StockResponse stock,
        Instant saleStartsAt,
        Instant saleEndsAt
) {
}
