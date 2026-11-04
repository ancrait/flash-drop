package com.sorokaandriy.inventory_service.service;

import com.sorokaandriy.inventory_service.dto.request.AdjustStockRequest;
import com.sorokaandriy.inventory_service.dto.request.CreateProductRequest;
import com.sorokaandriy.inventory_service.dto.request.UpdateProductRequest;
import com.sorokaandriy.inventory_service.dto.response.ProductResponse;
import com.sorokaandriy.inventory_service.entity.Product;
import com.sorokaandriy.inventory_service.entity.Stock;
import com.sorokaandriy.inventory_service.exception.ProductNotFoundException;
import com.sorokaandriy.inventory_service.repository.ProductRepository;
import com.sorokaandriy.inventory_service.repository.StockRepository;
import com.sorokaandriy.inventory_service.service.mapper.ProductMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {
    @Mock
    private ProductRepository productRepository;
    @Mock
    private StockRepository stockRepository;

    private ProductService service;
    private Product product;

    @BeforeEach
    void setUp() {
        service = new ProductService(new ProductMapper(), productRepository, stockRepository);
        product = Product.builder()
                .id(UUID.randomUUID()).sku("SKU-1").name("Keyboard")
                .priceMinor(15000L).currency("UAH").active(true).build();
        product.setStock(Stock.builder().id(UUID.randomUUID()).product(product)
                .onHand(10L).reserved(3L).build());
    }

    @Test
    void createsProductWithSaleWindowAndExposesItInResponse() {
        Instant start = Instant.parse("2026-11-01T10:00:00Z");
        Instant end = start.plusSeconds(3600);
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ProductResponse result = service.createProduct(
                new CreateProductRequest("SALE", "Sale product", null, 100L, "UAH", start, end));

        assertThat(result.saleStartsAt()).isEqualTo(start);
        assertThat(result.saleEndsAt()).isEqualTo(end);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs = {0, -1})
    void rejectsInvalidSaleWindowBeforeSaving(long endOffset) {
        Instant start = Instant.parse("2026-11-01T10:00:00Z");

        assertThatThrownBy(() -> service.createProduct(new CreateProductRequest(
                "SALE", "Sale product", null, 100L, "UAH", start, start.plusSeconds(endOffset))))
                .isInstanceOf(com.sorokaandriy.inventory_service.exception.InvalidSaleWindowException.class);

        verifyNoInteractions(productRepository);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void validatesSingleBoundPatchAgainstExistingOtherBound(boolean changesStart) {
        Instant start = Instant.parse("2026-11-01T10:00:00Z");
        Instant end = start.plusSeconds(3600);
        product.setSaleStartsAt(start);
        product.setSaleEndsAt(end);
        when(stockRepository.findByProductId(product.getId())).thenReturn(Optional.of(product.getStock()));
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        UpdateProductRequest patch = new UpdateProductRequest("Changed", null, null,
                changesStart ? end : null, changesStart ? null : start);

        assertThatThrownBy(() -> service.updateProduct(product.getId(), patch))
                .isInstanceOf(com.sorokaandriy.inventory_service.exception.InvalidSaleWindowException.class);

        assertThat(product.getName()).isEqualTo("Keyboard");
        assertThat(product.getSaleStartsAt()).isEqualTo(start);
        assertThat(product.getSaleEndsAt()).isEqualTo(end);
        verify(productRepository, never()).save(any());
    }

    @Test
    void updatesSaleWindowAndPreservesOmittedBound() {
        Instant start = Instant.parse("2026-11-01T10:00:00Z");
        Instant end = start.plusSeconds(3600);
        product.setSaleStartsAt(start);
        product.setSaleEndsAt(end);
        when(stockRepository.findByProductId(product.getId())).thenReturn(Optional.of(product.getStock()));
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));

        ProductResponse result = service.updateProduct(product.getId(),
                new UpdateProductRequest(null, null, null, start.plusSeconds(1), null));

        assertThat(result.saleStartsAt()).isEqualTo(start.plusSeconds(1));
        assertThat(result.saleEndsAt()).isEqualTo(end);
    }

    @Test
    void getsActiveProductWithAvailableStock() {
        when(productRepository.findByIdAndActiveTrue(product.getId())).thenReturn(Optional.of(product));

        ProductResponse result = service.getProduct(product.getId());

        assertThat(result.id()).isEqualTo(product.getId());
        assertThat(result.name()).isEqualTo("Keyboard");
        assertThat(result.stock().available()).isEqualTo(7L);
        verify(productRepository).findByIdAndActiveTrue(product.getId());
        verifyNoMoreInteractions(productRepository);
    }

    @Test
    void failsWhenActiveProductDoesNotExist() {
        when(productRepository.findByIdAndActiveTrue(product.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProduct(product.getId()))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining(product.getId().toString());
        verifyNoInteractions(stockRepository);
    }

    @Test
    void mapsCatalogAndPreservesPagination() {
        PageRequest pageable = PageRequest.of(1, 2, Sort.by("name"));
        when(productRepository.findAllByActiveTrue(pageable))
                .thenReturn(new PageImpl<>(List.of(product), pageable, 3));

        Page<ProductResponse> result = service.getProducts(pageable);

        assertThat(result.getContent()).extracting(ProductResponse::id).containsExactly(product.getId());
        assertThat(result.getNumber()).isEqualTo(1);
        assertThat(result.getSize()).isEqualTo(2);
        assertThat(result.getTotalElements()).isEqualTo(3);
        assertThat(result.getSort()).isEqualTo(pageable.getSort());
    }

    @Test
    void returnsEmptyCatalog() {
        PageRequest pageable = PageRequest.of(0, 20);
        when(productRepository.findAllByActiveTrue(pageable)).thenReturn(Page.empty(pageable));

        assertThat(service.getProducts(pageable)).isEmpty();
    }

    @Test
    void createsActiveProductWithZeroStockAndReturnsPersistedIds() {
        CreateProductRequest request = new CreateProductRequest("NEW-1", "Mouse", "Wireless", 5000L, "UAH", null, null);
        UUID productId = UUID.randomUUID();
        UUID stockId = UUID.randomUUID();
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> {
            Product saved = invocation.getArgument(0);
            assertThat(saved.getSku()).isEqualTo(request.sku());
            assertThat(saved.getDescription()).isEqualTo(request.description());
            assertThat(saved.isActive()).isTrue();
            assertThat(saved.getStock().getProduct()).isSameAs(saved);
            assertThat(saved.getStock().getOnHand()).isZero();
            assertThat(saved.getStock().getReserved()).isZero();
            saved.setId(productId);
            saved.getStock().setId(stockId);
            return saved;
        });

        ProductResponse result = service.createProduct(request);

        assertThat(result.id()).isEqualTo(productId);
        assertThat(result.stock().id()).isEqualTo(stockId);
        assertThat(result.stock().productId()).isEqualTo(productId);
        assertThat(result.name()).isEqualTo(request.name());
        assertThat(result.priceMinor()).isEqualTo(request.priceMinor());
        verifyNoInteractions(stockRepository);
    }

    @Test
    void updatesAndDeactivatesProduct() {
        when(stockRepository.findByProductId(product.getId())).thenReturn(Optional.of(product.getStock()));
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));

        ProductResponse result = service.updateProduct(product.getId(), new UpdateProductRequest("Updated", 20000L, false, null, null));

        assertThat(result.name()).isEqualTo("Updated");
        assertThat(result.priceMinor()).isEqualTo(20000L);
        assertThat(result.active()).isFalse();
        assertThat(result.updatedAt()).isNotNull();
        var order = inOrder(stockRepository, productRepository);
        order.verify(stockRepository).findByProductId(product.getId());
        order.verify(productRepository).findById(product.getId());
        order.verify(productRepository).save(product);
    }

    @Test
    void allowsReactivatingInactiveProduct() {
        product.setActive(false);
        when(stockRepository.findByProductId(product.getId())).thenReturn(Optional.of(product.getStock()));
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));

        ProductResponse result = service.updateProduct(product.getId(), new UpdateProductRequest(null, null, true, null, null));

        assertThat(result.active()).isTrue();
        assertThat(result.name()).isEqualTo("Keyboard");
        assertThat(result.priceMinor()).isEqualTo(15000L);
    }

    @Test
    void doesNotSaveWhenProductToUpdateIsMissing() {
        when(stockRepository.findByProductId(product.getId())).thenReturn(Optional.of(product.getStock()));
        when(productRepository.findById(product.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateProduct(product.getId(), new UpdateProductRequest("Updated", null, null, null, null)))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining(product.getId().toString());
        verify(productRepository, never()).save(any());
    }

    @Test
    void increasesOnHandWithoutChangingReservations() {
        Stock stock = product.getStock();
        stock.setUpdatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        when(stockRepository.findByProductId(product.getId())).thenReturn(Optional.of(stock));
        Instant before = Instant.now();

        var result = service.stockAdjustment(product.getId(), new AdjustStockRequest(5L));

        assertThat(stock.getOnHand()).isEqualTo(15L);
        assertThat(stock.getReserved()).isEqualTo(3L);
        assertThat(stock.getUpdatedAt()).isBetween(before, Instant.now());
        assertThat(result.onHand()).isEqualTo(15L);
        assertThat(result.available()).isEqualTo(12L);
        assertThat(result.updatedAt()).isEqualTo(stock.getUpdatedAt());
    }

    @Test
    void failsWhenStockToAdjustIsMissing() {
        when(stockRepository.findByProductId(product.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.stockAdjustment(product.getId(), new AdjustStockRequest(5L)))
                .isInstanceOf(ProductNotFoundException.class)
                .hasMessageContaining(product.getId().toString());
        verifyNoInteractions(productRepository);
    }
}
