package com.sorokaandriy.inventory_service.service.mapper;

import com.sorokaandriy.inventory_service.dto.response.ReservationItemResponse;
import com.sorokaandriy.inventory_service.dto.response.ReservationResponse;
import com.sorokaandriy.inventory_service.entity.Reservation;
import com.sorokaandriy.inventory_service.entity.ReservationItem;
import org.springframework.stereotype.Component;

import java.util.Comparator;

@Component
public class ReservationMapper {

    public ReservationResponse toResponse(Reservation reservation) {
        return ReservationResponse.builder()
                .id(reservation.getId())
                .orderId(reservation.getOrderId())
                .status(reservation.getStatus())
                .rejectionReason(reservation.getRejectionReason())
                .createdAt(reservation.getCreatedAt())
                .updatedAt(reservation.getUpdatedAt())
                .items(reservation.getReservationItems().stream()
                        .sorted(Comparator.comparing(item -> item.getProduct().getId().toString()))
                        .map(this::toItemResponse)
                        .toList())
                .build();
    }

    private ReservationItemResponse toItemResponse(ReservationItem item) {
        return ReservationItemResponse.builder()
                .id(item.getId())
                .productId(item.getProduct().getId())
                .quantity(item.getQuantity())
                .createdAt(item.getCreatedAt())
                .build();
    }
}
