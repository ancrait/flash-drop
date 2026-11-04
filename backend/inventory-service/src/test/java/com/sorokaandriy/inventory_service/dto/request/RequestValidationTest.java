package com.sorokaandriy.inventory_service.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RequestValidationTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        factory.close();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("validRequests")
    void acceptsValidRequest(String scenario, Object request) {
        assertThat(validator.validate(request)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRequests")
    void rejectsInvalidRequestAtExpectedField(String scenario, Object request, String path) {
        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains(path);
    }

    private static Stream<Arguments> validRequests() {
        return Stream.of(
                Arguments.of("create product without description", new CreateProductRequest("SKU", "Product", null, 1L, "UAH", null, null)),
                Arguments.of("create product with maximum name length", new CreateProductRequest("SKU", "x".repeat(256), "Description", 100L, "UAH", null, null)),
                Arguments.of("empty PATCH", new UpdateProductRequest(null, null, null, null, null)),
                Arguments.of("deactivate product", new UpdateProductRequest("Product", 1L, false, null, null)),
                Arguments.of("PATCH with maximum name length", new UpdateProductRequest("x".repeat(256), null, null, null, null)),
                Arguments.of("positive stock adjustment", new AdjustStockRequest(1L)),
                Arguments.of("reservation", reservation(List.of(new ReservationItemRequest(ID, 1L, 1L)))),
                Arguments.of("commit", new CommitReservationRequest(ID)),
                Arguments.of("release", new ReleaseReservationRequest(ID))
        );
    }

    private static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("missing SKU", new CreateProductRequest(null, "Product", null, 100L, "UAH", null, null), "sku"),
                Arguments.of("blank SKU", new CreateProductRequest(" \t", "Product", null, 100L, "UAH", null, null), "sku"),
                Arguments.of("missing name", new CreateProductRequest("SKU", null, null, 100L, "UAH", null, null), "name"),
                Arguments.of("blank name", new CreateProductRequest("SKU", " \n", null, 100L, "UAH", null, null), "name"),
                Arguments.of("long name", new CreateProductRequest("SKU", "x".repeat(257), null, 100L, "UAH", null, null), "name"),
                Arguments.of("missing product price", new CreateProductRequest("SKU", "Product", null, null, "UAH", null, null), "priceMinor"),
                Arguments.of("zero product price", new CreateProductRequest("SKU", "Product", null, 0L, "UAH", null, null), "priceMinor"),
                Arguments.of("negative product price", new CreateProductRequest("SKU", "Product", null, -1L, "UAH", null, null), "priceMinor"),
                Arguments.of("missing product currency", new CreateProductRequest("SKU", "Product", null, 100L, null, null, null), "currency"),
                Arguments.of("blank product currency", new CreateProductRequest("SKU", "Product", null, 100L, "", null, null), "currency"),
                Arguments.of("unsupported product currency", new CreateProductRequest("SKU", "Product", null, 100L, "USD", null, null), "currency"),
                Arguments.of("blank PATCH name", new UpdateProductRequest(" \t\n", null, null, null, null), "name"),
                Arguments.of("empty PATCH name", new UpdateProductRequest("", null, null, null, null), "name"),
                Arguments.of("long PATCH name", new UpdateProductRequest("x".repeat(257), null, null, null, null), "name"),
                Arguments.of("zero PATCH price", new UpdateProductRequest(null, 0L, null, null, null), "priceMinor"),
                Arguments.of("negative PATCH price", new UpdateProductRequest(null, -1L, null, null, null), "priceMinor"),
                Arguments.of("missing adjustment", new AdjustStockRequest(null), "quantity"),
                Arguments.of("zero adjustment", new AdjustStockRequest(0L), "quantity"),
                Arguments.of("negative adjustment", new AdjustStockRequest(-1L), "quantity"),
                Arguments.of("missing order", new CreateReservationRequest(null, "UAH", List.of(validItem())), "orderId"),
                Arguments.of("missing reservation currency", new CreateReservationRequest(ID, null, List.of(validItem())), "currency"),
                Arguments.of("blank reservation currency", new CreateReservationRequest(ID, " ", List.of(validItem())), "currency"),
                Arguments.of("unsupported reservation currency", new CreateReservationRequest(ID, "USD", List.of(validItem())), "currency"),
                Arguments.of("missing items", reservation(null), "items"),
                Arguments.of("empty items", reservation(List.of()), "items"),
                Arguments.of("null list element", reservation(Arrays.asList((ReservationItemRequest) null)), "items[0].<list element>"),
                Arguments.of("missing nested product", reservation(List.of(new ReservationItemRequest(null, 1L, 100L))), "items[0].productId"),
                Arguments.of("missing nested quantity", reservation(List.of(new ReservationItemRequest(ID, null, 100L))), "items[0].quantity"),
                Arguments.of("zero nested quantity", reservation(List.of(new ReservationItemRequest(ID, 0L, 100L))), "items[0].quantity"),
                Arguments.of("negative nested quantity", reservation(List.of(new ReservationItemRequest(ID, -1L, 100L))), "items[0].quantity"),
                Arguments.of("missing nested price", reservation(List.of(new ReservationItemRequest(ID, 1L, null))), "items[0].expectedPriceMinor"),
                Arguments.of("zero nested price", reservation(List.of(new ReservationItemRequest(ID, 1L, 0L))), "items[0].expectedPriceMinor"),
                Arguments.of("negative nested price", reservation(List.of(new ReservationItemRequest(ID, 1L, -1L))), "items[0].expectedPriceMinor"),
                Arguments.of("missing commit order", new CommitReservationRequest(null), "orderId"),
                Arguments.of("missing release order", new ReleaseReservationRequest(null), "orderId")
        );
    }

    private static CreateReservationRequest reservation(List<ReservationItemRequest> items) {
        return new CreateReservationRequest(ID, "UAH", items);
    }

    private static ReservationItemRequest validItem() {
        return new ReservationItemRequest(ID, 1L, 100L);
    }
}
