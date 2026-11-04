package com.sorokaandriy.inventory_service.service.mapper;

import com.sorokaandriy.inventory_service.dto.request.CreateProductRequest;
import com.sorokaandriy.inventory_service.dto.request.UpdateProductRequest;
import com.sorokaandriy.inventory_service.dto.response.ProductResponse;
import com.sorokaandriy.inventory_service.dto.response.StockResponse;
import com.sorokaandriy.inventory_service.entity.Product;
import com.sorokaandriy.inventory_service.entity.Stock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProductMapperTest {
    private final ProductMapper mapper = new ProductMapper();

    @Test
    void mapsAllProductAndStockFields() {
        Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
        Instant updatedAt = createdAt.plusSeconds(60);
        Product product = Product.builder().id(UUID.randomUUID()).sku("SKU-1").name("Keyboard")
                .description("Mechanical").priceMinor(15000L).currency("UAH").active(false)
                .createdAt(createdAt).updatedAt(updatedAt).build();
        Stock stock = Stock.builder().id(UUID.randomUUID()).product(product)
                .onHand(10L).reserved(3L).updatedAt(updatedAt).build();
        product.setStock(stock);

        ProductResponse result = mapper.fromProductToProductResponse(product);

        assertThat(result).isEqualTo(new ProductResponse(product.getId(), "SKU-1", "Keyboard", "Mechanical",
                15000L, "UAH", false, createdAt, updatedAt,
                new StockResponse(stock.getId(), product.getId(), 10L, 3L, 7L, updatedAt), null, null));
    }

    @ParameterizedTest
    @CsvSource({"10, 3, 7", "10, 10, 0", "0, 0, 0"})
    void calculatesAvailableStock(long onHand, long reserved, long available) {
        Product product = Product.builder().id(UUID.randomUUID()).build();
        Stock stock = Stock.builder().product(product).onHand(onHand).reserved(reserved).build();

        assertThat(mapper.fromStockToStockResponse(stock).available()).isEqualTo(available);
    }

    @Test
    void createsActiveProductWithBidirectionalZeroStock() {
        CreateProductRequest request = new CreateProductRequest("SKU-1", "Keyboard", null, 15000L, "UAH", null, null);
        Instant before = Instant.now();

        Product product = mapper.fromProductRequestToProduct(request);

        assertThat(product.getId()).isNull();
        assertThat(product.getSku()).isEqualTo(request.sku());
        assertThat(product.getName()).isEqualTo(request.name());
        assertThat(product.getDescription()).isNull();
        assertThat(product.getPriceMinor()).isEqualTo(request.priceMinor());
        assertThat(product.getCurrency()).isEqualTo(request.currency());
        assertThat(product.isActive()).isTrue();
        assertThat(product.getCreatedAt()).isBetween(before, Instant.now());
        assertThat(product.getStock().getProduct()).isSameAs(product);
        assertThat(product.getStock().getOnHand()).isZero();
        assertThat(product.getStock().getReserved()).isZero();
    }

    @Test
    void appliesPatchFieldsIncludingFalseWithoutChangingOtherFields() {
        Product product = existingProduct();
        Stock originalStock = product.getStock();
        UUID originalId = product.getId();
        Instant createdAt = product.getCreatedAt();
        Instant before = Instant.now();

        mapper.updateProduct(product, new UpdateProductRequest("New name", 20000L, false, null, null));

        assertThat(product.getName()).isEqualTo("New name");
        assertThat(product.getPriceMinor()).isEqualTo(20000L);
        assertThat(product.isActive()).isFalse();
        assertThat(product.getId()).isEqualTo(originalId);
        assertThat(product.getSku()).isEqualTo("SKU-1");
        assertThat(product.getDescription()).isEqualTo("Original description");
        assertThat(product.getCurrency()).isEqualTo("UAH");
        assertThat(product.getCreatedAt()).isEqualTo(createdAt);
        assertThat(product.getStock()).isSameAs(originalStock);
        assertThat(product.getUpdatedAt()).isBetween(before, Instant.now());
    }

    @Test
    void leavesNullPatchFieldsUnchanged() {
        Product product = existingProduct();
        Instant before = Instant.now();

        mapper.updateProduct(product, new UpdateProductRequest(null, null, null, null, null));

        assertThat(product.getName()).isEqualTo("Keyboard");
        assertThat(product.getPriceMinor()).isEqualTo(15000L);
        assertThat(product.isActive()).isTrue();
        assertThat(product.getUpdatedAt()).isBetween(before, Instant.now());
    }

    private Product existingProduct() {
        return Product.builder().id(UUID.randomUUID()).sku("SKU-1").name("Keyboard")
                .description("Original description").priceMinor(15000L).currency("UAH").active(true)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .stock(Stock.builder().onHand(10L).reserved(3L).build()).build();
    }
}
