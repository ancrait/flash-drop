package com.sorokaandriy.inventory_service.dto.response;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record StockResponse(
        UUID id,
        UUID productId,
        Long onHand,
        Long reserved,
        Long available,
        Instant updatedAt
) {
}
