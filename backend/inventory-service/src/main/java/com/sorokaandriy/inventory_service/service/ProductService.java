package com.sorokaandriy.inventory_service.service;

import com.sorokaandriy.inventory_service.dto.request.AdjustStockRequest;
import com.sorokaandriy.inventory_service.dto.request.CreateProductRequest;
import com.sorokaandriy.inventory_service.dto.request.UpdateProductRequest;
import com.sorokaandriy.inventory_service.dto.response.ProductResponse;
import com.sorokaandriy.inventory_service.dto.response.StockResponse;
import com.sorokaandriy.inventory_service.entity.Product;
import com.sorokaandriy.inventory_service.entity.Stock;
import com.sorokaandriy.inventory_service.exception.ProductNotFoundException;
import com.sorokaandriy.inventory_service.exception.InvalidSaleWindowException;
import com.sorokaandriy.inventory_service.repository.ProductRepository;
import com.sorokaandriy.inventory_service.repository.StockRepository;
import com.sorokaandriy.inventory_service.service.mapper.ProductMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

import java.time.Instant;
import java.util.UUID;

@RequiredArgsConstructor
@Slf4j
@Service
public class ProductService {

    private final ProductMapper mapper;
    private final ProductRepository productRepository;
    private final StockRepository stockRepository;


    @Transactional(readOnly = true)
    public ProductResponse getProduct(UUID id) {

        log.info("Getting product {}", id);

        Product product = productRepository.findByIdAndActiveTrue(id)
                .orElseThrow(() -> new ProductNotFoundException("Product with id " + id + " not found"));

        return mapper.fromProductToProductResponse(product);

    }


    @Transactional(readOnly = true)
    public Page<ProductResponse> getProducts(Pageable pageable) {

        log.info("Getting products");

        return productRepository.findAllByActiveTrue(pageable)
                .map(mapper::fromProductToProductResponse);
    }


    @Transactional
    public ProductResponse createProduct(CreateProductRequest request) {

        validateSaleWindow(request.saleStartsAt(), request.saleEndsAt());
        Product product = mapper.fromProductRequestToProduct(request);

        log.info("Saving product {}", product.getId());
        return mapper.fromProductToProductResponse(productRepository.save(product));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProductResponse updateProduct(UUID id, UpdateProductRequest request) {

        // Reserve and product updates share this lock. Read the product only after
        // obtaining it, so validation sees the preceding transaction's changes.
        stockRepository.findByProductId(id)
                .orElseThrow(() -> new ProductNotFoundException("Product with id " + id + " not found"));

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException("Product with id " + id + " not found"));

        log.info("Update product {}", id);


        validateSaleWindow(
                request.saleStartsAt() != null ? request.saleStartsAt() : product.getSaleStartsAt(),
                request.saleEndsAt() != null ? request.saleEndsAt() : product.getSaleEndsAt());
        mapper.updateProduct(product, request);

        productRepository.save(product);

        return mapper.fromProductToProductResponse(product);
    }

    private void validateSaleWindow(Instant startsAt, Instant endsAt) {
        if (startsAt != null && endsAt != null && !startsAt.isBefore(endsAt)) {
            throw new InvalidSaleWindowException("saleStartsAt must be before saleEndsAt");
        }
    }

    @Transactional
    public StockResponse stockAdjustment(UUID id, AdjustStockRequest request) {

        Stock stock = stockRepository.findByProductId(id)
                .orElseThrow(() -> new ProductNotFoundException("Product with id " + id + " not found"));

        log.info("Adjust stock product {}", id);

       stock.setOnHand(stock.getOnHand() + request.quantity());
       stock.setUpdatedAt(Instant.now());

        return mapper.fromStockToStockResponse(stock);
    }
}
