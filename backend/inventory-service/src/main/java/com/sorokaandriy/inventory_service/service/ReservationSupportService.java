package com.sorokaandriy.inventory_service.service;

import com.sorokaandriy.inventory_service.dto.request.ReservationItemRequest;
import com.sorokaandriy.inventory_service.dto.response.ReservationResponse;
import com.sorokaandriy.inventory_service.entity.Product;
import com.sorokaandriy.inventory_service.entity.Reservation;
import com.sorokaandriy.inventory_service.entity.ReservationItem;
import com.sorokaandriy.inventory_service.entity.ReservationStatus;
import com.sorokaandriy.inventory_service.entity.Stock;
import com.sorokaandriy.inventory_service.exception.ReservationConflictException;
import com.sorokaandriy.inventory_service.repository.ReservationItemRepository;
import com.sorokaandriy.inventory_service.repository.StockRepository;
import com.sorokaandriy.inventory_service.service.mapper.ReservationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.Clock;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReservationSupportService {

    private final ReservationItemRepository reservationItemRepository;
    private final StockRepository stockRepository;
    private final ReservationMapper mapper;
    private final Clock clock;

    void releaseStocks(List<ReservationItem> items, Map<UUID, Stock> stocks) {
        for (ReservationItem item : items) {
            Stock stock = stocks.get(item.getProduct().getId());
            if (stock.getReserved() < item.getQuantity()) {
                throw new IllegalStateException(
                        "Insufficient reserved stock to release product " + item.getProduct().getId());
            }
        }

        Instant now = Instant.now();
        for (ReservationItem item : items) {
            Stock stock = stocks.get(item.getProduct().getId());
            stock.setReserved(stock.getReserved() - item.getQuantity());
            stock.setUpdatedAt(now);
        }
    }

    Map<UUID, Stock> lockReservationStocks(List<ReservationItem> sortedItems) {
        Map<UUID, Stock> stocks = new LinkedHashMap<>();

        for (ReservationItem item : sortedItems) {
            UUID productId = item.getProduct().getId();
            Stock stock = stockRepository.findByProductId(productId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Stock not found for reserved product " + productId));
            stocks.put(productId, stock);
        }

        return stocks;
    }

    void writeOffItems(List<ReservationItem> items, Map<UUID, Stock> stocks) {
        // Validate every position before changing any of the locked stocks.
        for (ReservationItem item : items) {
            Stock stock = stocks.get(item.getProduct().getId());
            if (stock.getOnHand() < item.getQuantity() || stock.getReserved() < item.getQuantity()) {
                throw new IllegalStateException(
                        "Insufficient stock to commit reserved product " + item.getProduct().getId());
            }
        }

        Instant now = Instant.now();
        for (ReservationItem item : items) {
            Stock stock = stocks.get(item.getProduct().getId());
            stock.setOnHand(stock.getOnHand() - item.getQuantity());
            stock.setReserved(stock.getReserved() - item.getQuantity());
            stock.setUpdatedAt(now);
        }
    }

    List<ReservationItemRequest> prepareItems(List<ReservationItemRequest> items) {
        Set<UUID> productIds = new HashSet<>();

        for (ReservationItemRequest item : items) {
            if (!productIds.add(item.productId())) {
                throw new IllegalArgumentException("Duplicate productId: " + item.productId());
            }
        }

        return items.stream()
                .sorted(Comparator.comparing(item -> item.productId().toString()))
                .toList();
    }

    String calculateRequestHash(String currency, List<ReservationItemRequest> sortedItems) {
        String items = sortedItems.stream()
                .map(item -> item.productId() + ":" + item.quantity() + ":" + item.expectedPriceMinor())
                .collect(Collectors.joining(";"));

        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest((currency + "|" + items).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    ReservationResponse replay(Reservation reservation, String requestHash) {
        // A release received before reserve must prevent any late reservation of this order.
        if (reservation.getStatus() == ReservationStatus.RELEASED && reservation.getRequestHash() == null) {
            return mapper.toResponse(reservation);
        }
        if (!reservation.getRequestHash().equals(requestHash)) {
            throw new ReservationConflictException(
                    "Different reservation request for order " + reservation.getOrderId());
        }

        return mapper.toResponse(reservation);
    }

    Map<UUID, Stock> lockStocks(List<ReservationItemRequest> sortedItems) {
        Map<UUID, Stock> stocks = new LinkedHashMap<>();

        // All inventory operations must acquire these locks in the same product ID order.
        for (ReservationItemRequest item : sortedItems) {
            stockRepository.findByProductId(item.productId())
                    .ifPresent(stock -> stocks.put(item.productId(), stock));
        }

        return stocks;
    }

    Optional<String> findRejectionReason(
            List<ReservationItemRequest> items, Map<UUID, Stock> stocks, String currency) {
        // All stock locks have been acquired; use the same instant for every item.
        Instant now = clock.instant();
        for (ReservationItemRequest item : items) {
            Stock stock = stocks.get(item.productId());

            if (stock == null) {
                return Optional.of("Stock not found for product " + item.productId());
            }

            Product product = stock.getProduct();

            if (!product.isActive()) {
                return Optional.of("Product is inactive: " + item.productId());
            }
            if (product.getSaleStartsAt() != null && now.isBefore(product.getSaleStartsAt())) {
                return Optional.of("Sale has not started for product " + item.productId());
            }
            if (product.getSaleEndsAt() != null && !now.isBefore(product.getSaleEndsAt())) {
                return Optional.of("Sale has ended for product " + item.productId());
            }
            if (!product.getCurrency().equals(currency)) {
                return Optional.of("Currency mismatch for product " + item.productId());
            }
            if (!product.getPriceMinor().equals(item.expectedPriceMinor())) {
                return Optional.of("Price changed for product " + item.productId());
            }
            if (stock.getOnHand() - stock.getReserved() < item.quantity()) {
                return Optional.of("Insufficient stock for product " + item.productId());
            }
        }

        return Optional.empty();
    }

    ReservationResponse reject(Reservation reservation, String reason) {
        reservation.setStatus(ReservationStatus.REJECTED);
        reservation.setRejectionReason(reason);
        reservation.setUpdatedAt(Instant.now());

        return mapper.toResponse(reservation);
    }

    void saveItems(
            Reservation reservation, List<ReservationItemRequest> items, Map<UUID, Stock> stocks) {
        List<ReservationItem> reservationItems = items.stream()
                .map(item -> ReservationItem.builder()
                        .reservation(reservation)
                        .product(stocks.get(item.productId()).getProduct())
                        .quantity(item.quantity())
                        .build())
                .toList();

        reservation.setReservationItems(reservationItemRepository.saveAll(reservationItems));
    }

    void reserveStocks(List<ReservationItemRequest> items, Map<UUID, Stock> stocks) {
        Instant now = Instant.now();

        for (ReservationItemRequest item : items) {
            Stock stock = stocks.get(item.productId());
            stock.setReserved(stock.getReserved() + item.quantity());
            stock.setUpdatedAt(now);
        }
    }
}
