package com.sorokaandriy.inventory_service.service;

import com.sorokaandriy.inventory_service.dto.request.*;
import com.sorokaandriy.inventory_service.dto.response.ReservationResponse;
import com.sorokaandriy.inventory_service.entity.ReservationStatus;
import com.sorokaandriy.inventory_service.exception.ReservationConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private final List<UUID> productIds = new ArrayList<>();
    private final List<UUID> orderIds = new ArrayList<>();

    @BeforeEach
    void createProduct() {
        productId = createProductWithStock(3L);
        orderId = newOrderId();
    }

    private UUID createProductWithStock(long quantity) {
        UUID id = products.createProduct(new CreateProductRequest(
                "CONCURRENT-" + UUID.randomUUID(), "Concurrent product", null,
                100L, "UAH", null, null)).id();
        productIds.add(id);
        products.stockAdjustment(id, new AdjustStockRequest(quantity));
        return id;
    }

    private UUID newOrderId() {
        UUID id = UUID.randomUUID();
        orderIds.add(id);
        return id;
    }

    @AfterEach
    void removeOnlyThisTestsData() {
        for (UUID id : orderIds) {
            jdbc.update("DELETE FROM reservation_items WHERE reservation_id IN "
                    + "(SELECT id FROM reservations WHERE order_id = ?)", id);
            jdbc.update("DELETE FROM reservations WHERE order_id = ?", id);
        }
        for (UUID id : productIds) {
            jdbc.update("DELETE FROM stocks WHERE product_id = ?", id);
            jdbc.update("DELETE FROM products WHERE id = ?", id);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"price", "active", "start", "end"})
    void updateFirstMakesReserveWaitAndValidateNewConditions(String change) throws Exception {
        ReservationResponse result = runConcurrent(() -> products.updateProduct(productId, patch(change)),
                () -> reservations.reserve(request())).second();

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
        ReservationResponse result = runConcurrent(() -> reservations.reserve(request()),
                () -> products.updateProduct(productId, patch(change))).first();

        assertThat(result.status()).isEqualTo(ReservationStatus.RESERVED);
        assertStock(3L, 1L);
        assertThat(reservations.commit(new CommitReservationRequest(orderId)).status())
                .isEqualTo(ReservationStatus.COMMITTED);
        assertStock(2L, 0L);
    }

    @Test
    void onlyOneBuyerCanReserveTheLastUnit() throws Exception {
        UUID lastUnit = createProductWithStock(1L);
        UUID competingOrder = newOrderId();

        var results = runConcurrent(
                () -> reservations.reserve(request(orderId, lastUnit)),
                () -> reservations.reserve(request(competingOrder, lastUnit)));

        assertThat(results.first().status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(results.second().status()).isEqualTo(ReservationStatus.REJECTED);
        assertThat(results.second().rejectionReason()).isEqualTo("Insufficient stock for product " + lastUnit);
        assertStock(lastUnit, 1L, 1L);
        assertStoredReservation(orderId, ReservationStatus.RESERVED, 1L);
        assertStoredReservation(competingOrder, ReservationStatus.REJECTED, 0L);

        assertThat(reservations.commit(new CommitReservationRequest(orderId)).status())
                .isEqualTo(ReservationStatus.COMMITTED);
        assertStock(lastUnit, 0L, 0L);
    }

    @Test
    void losingBuyerDoesNotReserveTheAvailableItemFromItsCart() throws Exception {
        // Put the unavailable product second in the service's lock and validation order.
        List<UUID> items = List.of(productId, createProductWithStock(3L)).stream()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        UUID available = items.getFirst();
        UUID contested = items.getLast();
        UUID competingOrder = newOrderId();

        var results = runConcurrent(
                () -> reservations.reserve(new CreateReservationRequest(orderId, "UAH",
                        List.of(new ReservationItemRequest(contested, 3L, 100L)))),
                () -> reservations.reserve(request(competingOrder, available, contested)));

        assertThat(results.first().status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(results.second().status()).isEqualTo(ReservationStatus.REJECTED);
        assertThat(results.second().rejectionReason()).isEqualTo("Insufficient stock for product " + contested);
        assertThat(results.second().items()).isEmpty();
        assertStock(available, 3L, 0L);
        assertStock(contested, 3L, 3L);
        assertStoredReservation(competingOrder, ReservationStatus.REJECTED, 0L);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentRetriesApplyReserveAndFinalCommandOnlyOnce(boolean commit) throws Exception {
        var reserveResults = runConcurrent(() -> reservations.reserve(request()),
                () -> reservations.reserve(request()));

        assertSameReservation(reserveResults, ReservationStatus.RESERVED);
        assertStock(3L, 1L);
        assertStoredReservation(orderId, ReservationStatus.RESERVED, 1L);

        Supplier<ReservationResponse> finalCommand = commit
                ? () -> reservations.commit(new CommitReservationRequest(orderId))
                : () -> reservations.release(new ReleaseReservationRequest(orderId));
        ReservationStatus finalStatus = commit ? ReservationStatus.COMMITTED : ReservationStatus.RELEASED;
        var finalResults = runConcurrent(finalCommand, finalCommand);

        assertSameReservation(finalResults, finalStatus);
        assertThat(finalResults.first().id()).isEqualTo(reserveResults.first().id());
        assertStock(commit ? 2L : 3L, 0L);
        assertStoredReservation(orderId, finalStatus, 1L);
    }

    @Test
    void concurrentReserveWithDifferentQuantityConflictsForTheSameOrder() {
        var changedRequest = new CreateReservationRequest(orderId, "UAH",
                List.of(new ReservationItemRequest(productId, 2L, 100L)));

        assertThatThrownBy(() -> runConcurrent(() -> reservations.reserve(request()),
                () -> reservations.reserve(changedRequest)))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(ReservationConflictException.class);

        assertStock(3L, 1L);
        assertStoredReservation(orderId, ReservationStatus.RESERVED, 1L);
        assertThat(reservations.reserve(request()).items()).singleElement()
                .satisfies(item -> assertThat(item.quantity()).isEqualTo(1L));
    }

    @Test
    void overlappingCartsInReverseOrderBothReserveWithoutDeadlock() throws Exception {
        List<UUID> items = List.of(productId, createProductWithStock(3L)).stream()
                .sorted(Comparator.comparing(UUID::toString))
                .toList();
        UUID firstProduct = items.getFirst();
        UUID secondProduct = items.getLast();
        UUID otherOrder = newOrderId();

        var results = runConcurrent(waitForSecondToBlock -> {
            // Hold only the first stock row so reversed locking by the second cart can cause a deadlock.
            jdbc.queryForObject("SELECT product_id FROM stocks WHERE product_id = ? FOR UPDATE",
                    UUID.class, firstProduct);
            waitForSecondToBlock.run();
            return reservations.reserve(request(orderId, firstProduct, secondProduct));
        }, () -> reservations.reserve(request(otherOrder, secondProduct, firstProduct)));

        assertThat(results.first().status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(results.second().status()).isEqualTo(ReservationStatus.RESERVED);
        assertStoredReservation(orderId, ReservationStatus.RESERVED, 2L);
        assertStoredReservation(otherOrder, ReservationStatus.RESERVED, 2L);
        assertStock(firstProduct, 3L, 2L);
        assertStock(secondProduct, 3L, 2L);
    }

    @Test
    void stockWriteFailureRollsBackReservationAndItems() {
        UUID otherProduct = createProductWithStock(3L);
        CreateReservationRequest cart = request(orderId, productId, otherProduct);

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // PostgreSQL rolls back this test-only constraint along with the failed write.
            jdbc.execute("""
                    ALTER TABLE stocks ADD CONSTRAINT test_reject_reservation_stock_write
                    CHECK (product_id <> '%s' OR reserved = 0)
                    """.formatted(otherProduct));
            assertThat(reservations.reserve(cart).status()).isEqualTo(ReservationStatus.RESERVED);
        })).hasRootCauseInstanceOf(PSQLException.class)
                .hasStackTraceContaining("test_reject_reservation_stock_write");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservations WHERE order_id = ?",
                Long.class, orderId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reservation_items WHERE product_id IN (?, ?)",
                Long.class, productId, otherProduct)).isZero();
        assertStock(3L, 0L);
        assertStock(otherProduct, 3L, 0L);

        assertThat(reservations.reserve(cart).status()).isEqualTo(ReservationStatus.RESERVED);
        assertStoredReservation(orderId, ReservationStatus.RESERVED, 2L);
        assertStock(3L, 1L);
        assertStock(otherProduct, 3L, 1L);
    }

    @ParameterizedTest(name = "release arrives first: {0}")
    @ValueSource(booleans = {true, false})
    void concurrentReserveAndReleaseEndReleasedInEitherOrder(boolean releaseFirst) throws Exception {
        Supplier<ReservationResponse> reserve = () -> reservations.reserve(request());
        Supplier<ReservationResponse> release = () -> reservations.release(new ReleaseReservationRequest(orderId));

        var results = releaseFirst ? runConcurrent(release, reserve) : runConcurrent(reserve, release);

        assertThat(results.first().status())
                .isEqualTo(releaseFirst ? ReservationStatus.RELEASED : ReservationStatus.RESERVED);
        assertThat(results.second().status()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(results.second().id()).isEqualTo(results.first().id());
        assertStoredReservation(orderId, ReservationStatus.RELEASED, releaseFirst ? 0L : 1L);
        assertStock(3L, 0L);
        assertThat(reservations.reserve(request()).status()).isEqualTo(ReservationStatus.RELEASED);
        assertStock(3L, 0L);
    }

    private <A, B> ConcurrentResults<A, B> runConcurrent(Supplier<A> firstOperation, Supplier<B> secondOperation)
            throws Exception {
        return runConcurrent(waitForSecondToBlock -> {
            A response = firstOperation.get();
            waitForSecondToBlock.run();
            return response;
        }, secondOperation);
    }

    private <A, B> ConcurrentResults<A, B> runConcurrent(
            Function<Runnable, A> firstOperation, Supplier<B> secondOperation)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstHasLock = new CountDownLatch(1);
        CountDownLatch secondIsBlocked = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger secondPid = new AtomicInteger();
        try {
            Future<A> first = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(status -> firstOperation.apply(() -> {
                        firstHasLock.countDown();
                        await(secondIsBlocked);
                    })));
            assertThat(firstHasLock.await(10, TimeUnit.SECONDS)).isTrue();

            Future<B> second = executor.submit(() ->
                    new TransactionTemplate(transactionManager).execute(status -> {
                        secondPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        secondStarted.countDown();
                        return secondOperation.get();
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
            secondIsBlocked.countDown();
            return new ConcurrentResults<>(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        } finally {
            secondIsBlocked.countDown();
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
        return request(orderId, productId);
    }

    private CreateReservationRequest request(UUID order, UUID... productIds) {
        return new CreateReservationRequest(order, "UAH", List.of(productIds).stream()
                .map(id -> new ReservationItemRequest(id, 1L, 100L))
                .toList());
    }

    private void assertStock(long onHand, long reserved) {
        assertStock(productId, onHand, reserved);
    }

    private void assertStock(UUID product, long onHand, long reserved) {
        assertThat(jdbc.queryForObject("SELECT on_hand FROM stocks WHERE product_id = ?", Long.class, product))
                .isEqualTo(onHand);
        assertThat(jdbc.queryForObject("SELECT reserved FROM stocks WHERE product_id = ?", Long.class, product))
                .isEqualTo(reserved);
    }

    private void assertStoredReservation(UUID order, ReservationStatus status, long itemCount) {
        assertThat(jdbc.queryForList("SELECT status FROM reservations WHERE order_id = ?", String.class, order))
                .containsExactly(status.name());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM reservation_items item
                JOIN reservations reservation ON reservation.id = item.reservation_id
                WHERE reservation.order_id = ?
                """, Long.class, order)).isEqualTo(itemCount);
    }

    private void assertSameReservation(ConcurrentResults<ReservationResponse, ReservationResponse> results,
                                       ReservationStatus status) {
        assertThat(results.first().status()).isEqualTo(status);
        assertThat(results.second().status()).isEqualTo(status);
        assertThat(results.second().id()).isEqualTo(results.first().id());
    }

    private record ConcurrentResults<A, B>(A first, B second) {
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
