package com.sorokaandriy.inventory_service;

import com.sorokaandriy.inventory_service.entity.Product;
import com.sorokaandriy.inventory_service.entity.Reservation;
import com.sorokaandriy.inventory_service.entity.ReservationItem;
import com.sorokaandriy.inventory_service.entity.ReservationStatus;
import com.sorokaandriy.inventory_service.entity.Stock;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class InventoryServiceApplicationTests {


    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Product product;
    private Reservation reservation;

    @BeforeEach
    void persistInventoryGraph() {
        product = Product.builder()
                .sku("TEST-" + UUID.randomUUID())
                .name("Test product")
                .priceMinor(15000L)
                .currency("UAH")
                .active(true)
                .build();
        entityManager.persist(product);
        entityManager.persist(Stock.builder()
                .product(product)
                .onHand(10L)
                .reserved(2L)
                .build());
        reservation = Reservation.builder()
                .orderId(UUID.randomUUID())
                .status(ReservationStatus.RESERVED)
                .requestHash("test-request-hash")
                .build();
        entityManager.persist(reservation);
        entityManager.persist(ReservationItem.builder()
                .reservation(reservation)
                .product(product)
                .quantity(2L)
                .build());
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void persistsAndLoadsEntityRelationships() {
        Product loadedProduct = entityManager.find(Product.class, product.getId());
        Reservation loadedReservation = entityManager.find(Reservation.class, reservation.getId());

        assertEquals(15000L, loadedProduct.getPriceMinor());
        assertNotNull(loadedProduct.getVersion());
        assertEquals(10L, loadedProduct.getStock().getOnHand());
        assertEquals(2L, loadedProduct.getStock().getReserved());
        assertEquals(1, loadedProduct.getReservationItems().size());
        assertEquals(1, loadedReservation.getReservationItems().size());
        assertEquals(product.getId(), loadedReservation.getReservationItems().getFirst().getProduct().getId());
        assertEquals(ReservationStatus.RESERVED, loadedReservation.getStatus());
        assertNull(loadedReservation.getRejectionReason());
    }

    @Test
    void newStockHasZeroQuantities() {
        Product anotherProduct = Product.builder()
                .sku("ZERO-" + UUID.randomUUID())
                .name("New product")
                .priceMinor(100L)
                .currency("UAH")
                .build();
        entityManager.persist(anotherProduct);
        Stock stock = Stock.builder().product(anotherProduct).build();
        entityManager.persist(stock);
        entityManager.flush();
        entityManager.clear();

        Stock loadedStock = entityManager.find(Stock.class, stock.getId());
        assertEquals(0L, loadedStock.getOnHand());
        assertEquals(0L, loadedStock.getReserved());
    }

    @Test
    void rejectsDuplicateSku() {
        assertConstraint("uq_products_sku", () -> jdbcTemplate.update(
                "INSERT INTO products (sku, name, price_minor, currency) VALUES (?, 'Duplicate', 100, 'UAH')",
                product.getSku()));
    }

    @Test
    void rejectsSecondStockForProduct() {
        assertConstraint("uq_stocks_product", () -> jdbcTemplate.update(
                "INSERT INTO stocks (product_id) VALUES (?)", product.getId()));
    }

    @Test
    void rejectsSecondReservationForOrder() {
        assertConstraint("uq_reservations_order", () -> jdbcTemplate.update(
                "INSERT INTO reservations (order_id, status, request_hash) VALUES (?, 'RESERVED', 'another-hash')",
                reservation.getOrderId()));
    }

    @Test
    void rejectsDuplicateReservationItem() {
        assertConstraint("uq_reservation_items_reservation_product", () -> jdbcTemplate.update(
                "INSERT INTO reservation_items (reservation_id, product_id, quantity) VALUES (?, ?, 1)",
                reservation.getId(), product.getId()));
    }

    @ParameterizedTest
    @CsvSource({
            "'UPDATE stocks SET on_hand = -1', chk_stocks_",
            "'UPDATE stocks SET reserved = -1', chk_stocks_reserved",
            "'UPDATE stocks SET reserved = 11', chk_stocks_available",
            "'UPDATE products SET price_minor = 0', chk_products_price_minor",
            "'UPDATE products SET price_minor = -1', chk_products_price_minor",
            "'UPDATE reservation_items SET quantity = 0', chk_reservation_items_quantity",
            "'UPDATE reservation_items SET quantity = -1', chk_reservation_items_quantity",
            "'UPDATE reservations SET status = ''UNKNOWN''', chk_reservations_status"
    })
    void rejectsInvalidValues(String sql, String constraintName) {
        assertConstraint(constraintName, () -> jdbcTemplate.update(sql));
    }

    @Test
    void rejectsNullReservedQuantity() {
        assertConstraint("reserved", () -> jdbcTemplate.update("UPDATE stocks SET reserved = NULL"));
    }

    private void assertConstraint(String expectedName, Runnable operation) {
        DataIntegrityViolationException failure = assertThrows(DataIntegrityViolationException.class, operation::run);
        assertTrue(failure.getMostSpecificCause().getMessage().contains(expectedName), failure.getMessage());
    }

}
