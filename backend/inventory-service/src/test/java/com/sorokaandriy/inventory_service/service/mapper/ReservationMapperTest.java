package com.sorokaandriy.inventory_service.service.mapper;

import com.sorokaandriy.inventory_service.dto.response.ReservationItemResponse;
import com.sorokaandriy.inventory_service.dto.response.ReservationResponse;
import com.sorokaandriy.inventory_service.entity.Product;
import com.sorokaandriy.inventory_service.entity.Reservation;
import com.sorokaandriy.inventory_service.entity.ReservationItem;
import com.sorokaandriy.inventory_service.entity.ReservationStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReservationMapperTest {
    private final ReservationMapper mapper = new ReservationMapper();

    @Test
    void mapsAllFieldsAndSortsItemsWithoutChangingEntityOrder() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant updatedAt = createdAt.plusSeconds(60);
        ReservationItem first = item("70000000-0000-0000-0000-000000000001", 3L, createdAt);
        ReservationItem second = item("80000000-0000-0000-0000-000000000002", 2L, updatedAt);
        Reservation reservation = Reservation.builder().id(UUID.randomUUID()).orderId(UUID.randomUUID())
                .status(ReservationStatus.RESERVED).createdAt(createdAt).updatedAt(updatedAt)
                .reservationItems(new ArrayList<>(List.of(second, first))).build();

        ReservationResponse result = mapper.toResponse(reservation);

        assertThat(result).isEqualTo(new ReservationResponse(reservation.getId(), reservation.getOrderId(),
                ReservationStatus.RESERVED, null, createdAt, updatedAt, List.of(
                new ReservationItemResponse(first.getId(), first.getProduct().getId(), 3L, createdAt),
                new ReservationItemResponse(second.getId(), second.getProduct().getId(), 2L, updatedAt))));
        assertThat(reservation.getReservationItems()).containsExactly(second, first);
    }

    @Test
    void mapsRejectedReservationWithNoItems() {
        Reservation reservation = Reservation.builder().id(UUID.randomUUID()).orderId(UUID.randomUUID())
                .status(ReservationStatus.REJECTED).rejectionReason("Insufficient stock")
                .reservationItems(List.of()).build();

        ReservationResponse result = mapper.toResponse(reservation);

        assertThat(result.status()).isEqualTo(ReservationStatus.REJECTED);
        assertThat(result.rejectionReason()).isEqualTo("Insufficient stock");
        assertThat(result.items()).isEmpty();
    }

    private ReservationItem item(String productId, long quantity, Instant createdAt) {
        return ReservationItem.builder().id(UUID.randomUUID())
                .product(Product.builder().id(UUID.fromString(productId)).build())
                .quantity(quantity).createdAt(createdAt).build();
    }
}
