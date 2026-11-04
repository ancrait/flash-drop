package com.sorokaandriy.inventory_service.dto.response;

import com.sorokaandriy.inventory_service.entity.ReservationStatus;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder
public record ReservationResponse(
        UUID id,
        UUID orderId,
        ReservationStatus status,
        String rejectionReason,
        Instant createdAt,
        Instant updatedAt,
        List<ReservationItemResponse> items
) {
}
