package com.sorokaandriy.inventory_service.service;

import com.sorokaandriy.inventory_service.dto.request.CommitReservationRequest;
import com.sorokaandriy.inventory_service.dto.request.CreateReservationRequest;
import com.sorokaandriy.inventory_service.dto.request.ReleaseReservationRequest;
import com.sorokaandriy.inventory_service.dto.request.ReservationItemRequest;
import com.sorokaandriy.inventory_service.dto.response.ReservationResponse;
import com.sorokaandriy.inventory_service.entity.Reservation;
import com.sorokaandriy.inventory_service.entity.ReservationItem;
import com.sorokaandriy.inventory_service.entity.ReservationStatus;
import com.sorokaandriy.inventory_service.entity.Stock;
import com.sorokaandriy.inventory_service.exception.ReservationConflictException;
import com.sorokaandriy.inventory_service.repository.ReservationRepository;
import com.sorokaandriy.inventory_service.service.mapper.ReservationMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RequiredArgsConstructor
@Service
@Validated
public class ReservationService {

    private final ReservationRepository reservationRepository;
    private final ReservationSupportService support;
    private final ReservationMapper mapper;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationResponse reserve(@NotNull @Valid CreateReservationRequest request) {
        List<ReservationItemRequest> items = support.prepareItems(request.items());
        String requestHash = support.calculateRequestHash(request.currency(), items);

        boolean created = reservationRepository.insertIfAbsent(
                UUID.randomUUID(), request.orderId(), requestHash) == 1;

        Reservation reservation = reservationRepository.findByOrderId(request.orderId())
                .orElseThrow(() -> new IllegalStateException("Reservation was not found after insertion"));

        if (!created) {
            return support.replay(reservation, requestHash);
        }

        Map<UUID, Stock> stocks = support.lockStocks(items);
        Optional<String> rejection = support.findRejectionReason(items, stocks, request.currency());

        if (rejection.isPresent()) {
            return support.reject(reservation, rejection.get());
        }

        support.saveItems(reservation, items, stocks);
        support.reserveStocks(items, stocks);
        reservation.setUpdatedAt(Instant.now());

        return mapper.toResponse(reservation);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationResponse commit(@NotNull @Valid CommitReservationRequest request) {
        Reservation reservation = reservationRepository.findByOrderId(request.orderId())
                .orElseThrow(() -> new IllegalStateException(
                        "Reservation not found for order " + request.orderId()));

        if (reservation.getStatus() == ReservationStatus.COMMITTED) {
            return mapper.toResponse(reservation);
        }

        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            throw new ReservationConflictException(
                    "Cannot commit reservation in status " + reservation.getStatus());
        }

        List<ReservationItem> items = reservation.getReservationItems().stream()
                .sorted(Comparator.comparing(item -> item.getProduct().getId().toString()))
                .toList();

        if (items.isEmpty()) {
            throw new IllegalStateException("Reserved reservation has no items: " + reservation.getId());
        }

        Map<UUID, Stock> stocks = support.lockReservationStocks(items);
        support.writeOffItems(items, stocks);
        reservation.setStatus(ReservationStatus.COMMITTED);
        reservation.setUpdatedAt(Instant.now());

        return mapper.toResponse(reservation);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ReservationResponse release(@NotNull @Valid ReleaseReservationRequest request) {

        reservationRepository.insertReleasedIfAbsent(UUID.randomUUID(), request.orderId());
        Reservation reservation = reservationRepository.findByOrderId(request.orderId())
                .orElseThrow(() -> new IllegalStateException(
                        "Reservation not found for order " + request.orderId()));

        if (reservation.getStatus() == ReservationStatus.RELEASED
                || reservation.getStatus() == ReservationStatus.REJECTED) {
            return mapper.toResponse(reservation);
        }
        if (reservation.getStatus() != ReservationStatus.RESERVED) {
            throw new ReservationConflictException(
                    "Cannot release reservation in status " + reservation.getStatus());
        }

        List<ReservationItem> items = reservation.getReservationItems().stream()
                .sorted(Comparator.comparing(item -> item.getProduct().getId().toString()))
                .toList();
        if (items.isEmpty()) {
            throw new IllegalStateException("Reserved reservation has no items: " + reservation.getId());
        }

        Map<UUID, Stock> stocks = support.lockReservationStocks(items);
        support.releaseStocks(items, stocks);
        reservation.setStatus(ReservationStatus.RELEASED);
        reservation.setUpdatedAt(Instant.now());

        return mapper.toResponse(reservation);
    }
}
