package com.sorokaandriy.inventory_service.dto.response;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record ReservationItemResponse(
        UUID id,
        UUID productId,
        Long quantity,
        Instant createdAt
) {
}
