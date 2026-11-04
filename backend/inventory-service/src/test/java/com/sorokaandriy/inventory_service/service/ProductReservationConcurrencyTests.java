package com.sorokaandriy.inventory_service.service;

import com.sorokaandriy.inventory_service.dto.request.*;
import com.sorokaandriy.inventory_service.dto.response.ReservationResponse;
import com.sorokaandriy.inventory_service.entity.ReservationStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Timeout(30)
class ProductReservationConcurrencyTests {
    @Autowired private ProductService products;
    @Autowired private ReservationService reservations;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private UUID productId;
    private UUID orderId;

    @BeforeEach
    void createProduct() {
        productId = products.createProduct(new CreateProductRequest(
                "CONCURRENT-" + UUID.randomUUID(), "Concurrent product", null,
                100L, "UAH", null, null)).id();
        products.stockAdjustment(productId, new AdjustStockRequest(3L));
        orderId = UUID.randomUUID();
    }

    @AfterEach
    void removeOnlyThisTestsData() {
        jdbc.update("DELETE FROM reservation_items WHERE product_id = ?", productId);
        jdbc.update("DELETE FROM reservations WHERE order_id = ?", orderId);
        jdbc.update("DELETE FROM stocks WHERE product_id = ?", productId);
        jdbc.update("DELETE FROM products WHERE id = ?", productId);
    }

    @ParameterizedTest
    @ValueSource(strings = {"price", "active", "start", "end"})
    void updateFirstMakesReserveWaitAndValidateNewConditions(String change) throws Exception {
        ReservationResponse result = runConcurrent(change, true);

        assertThat(result.status()).isEqualTo(ReservationStatus.REJECTED);
        assertThat(result.rejectionReason()).contains(switch (change) {
            case "price" -> "Price changed";
            case "active" -> "inactive";
            case "start" -> "not started";
            default -> "ended";
        });
        assertStock(3L, 0L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation_items WHERE product_id = ?",
                Long.class, productId)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"price", "active", "start", "end"})
    void reserveFirstMakesUpdateWaitAndKeepsExistingReservationValid(String change) throws Exception {
        ReservationResponse result = runConcurrent(change, false);

        assertThat(result.status()).isEqualTo(ReservationStatus.RESERVED);
        assertStock(3L, 1L);
        assertThat(reservations.commit(new CommitReservationRequest(orderId)).status())
                .isEqualTo(ReservationStatus.COMMITTED);
        assertStock(2L, 0L);
    }

    private ReservationResponse runConcurrent(String change, boolean updateFirst) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstHasLock = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger secondPid = new AtomicInteger();
        try {
            Future<ReservationResponse> first = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(status -> {
                        ReservationResponse response = null;
                        if (updateFirst) products.updateProduct(productId, patch(change));
                        else response = reservations.reserve(request());
                        firstHasLock.countDown();
                        await(allowFirstCommit);
                        return response;
                    }));
            assertThat(firstHasLock.await(10, TimeUnit.SECONDS)).isTrue();

            Future<ReservationResponse> second = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(status -> {
                        secondPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        secondStarted.countDown();
                        if (updateFirst) return reservations.reserve(request());
                        products.updateProduct(productId, patch(change));
                        return null;
                    }));
            assertThat(secondStarted.await(10, TimeUnit.SECONDS)).isTrue();

            // Observe an actual PostgreSQL lock wait, rather than relying on thread timing.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            boolean blocked = false;
            while (!second.isDone() && System.nanoTime() < deadline) {
                blocked = Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean.class, secondPid.get()));
                if (blocked) break;
                Thread.sleep(20);
            }
            assertThat(blocked).as("second operation must wait for the first transaction's lock").isTrue();
            allowFirstCommit.countDown();
            ReservationResponse firstResponse = first.get(10, TimeUnit.SECONDS);
            ReservationResponse secondResponse = second.get(10, TimeUnit.SECONDS);
            return updateFirst ? secondResponse : firstResponse;
        } finally {
            allowFirstCommit.countDown();
            executor.shutdown();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow();
        }
    }

    private UpdateProductRequest patch(String change) {
        return switch (change) {
            case "price" -> new UpdateProductRequest(null, 200L, null, null, null);
            case "active" -> new UpdateProductRequest(null, null, false, null, null);
            case "start" -> new UpdateProductRequest(null, null, null, Instant.now().plusSeconds(86400), null);
            case "end" -> new UpdateProductRequest(null, null, null, null, Instant.now().minusSeconds(86400));
            default -> throw new IllegalArgumentException(change);
        };
    }

    private CreateReservationRequest request() {
        return new CreateReservationRequest(orderId, "UAH", List.of(new ReservationItemRequest(productId, 1L, 100L)));
    }

    private void assertStock(long onHand, long reserved) {
        assertThat(jdbc.queryForObject("SELECT on_hand FROM stocks WHERE product_id = ?", Long.class, productId))
                .isEqualTo(onHand);
        assertThat(jdbc.queryForObject("SELECT reserved FROM stocks WHERE product_id = ?", Long.class, productId))
                .isEqualTo(reserved);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for test transaction");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
