package com.sorokaandriy.inventory_service.service.mapper;

import com.sorokaandriy.inventory_service.dto.request.CreateProductRequest;
import com.sorokaandriy.inventory_service.dto.request.UpdateProductRequest;
import com.sorokaandriy.inventory_service.dto.response.ProductResponse;
import com.sorokaandriy.inventory_service.dto.response.StockResponse;
import com.sorokaandriy.inventory_service.entity.Product;
import com.sorokaandriy.inventory_service.entity.Stock;
import org.springframework.stereotype.Service;

import java.time.Instant;

@Service
public class ProductMapper {

    public ProductResponse fromProductToProductResponse(Product product) {

        return ProductResponse.builder()
                .id(product.getId())
                .sku(product.getSku())
                .name(product.getName())
                .description(product.getDescription())
                .priceMinor(product.getPriceMinor())
                .currency(product.getCurrency())
                .active(product.isActive())
                .saleStartsAt(product.getSaleStartsAt())
                .saleEndsAt(product.getSaleEndsAt())
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .stock(fromStockToStockResponse(product.getStock()))
                .build();
    }


    public StockResponse fromStockToStockResponse(Stock stock){

        return StockResponse.builder()
                .id(stock.getId())
                .productId(stock.getProduct().getId())
                .onHand(stock.getOnHand())
                .reserved(stock.getReserved())
                .available(stock.getOnHand() - stock.getReserved())
                .updatedAt(stock.getUpdatedAt())
                .build();
    }


    public Product fromProductRequestToProduct(CreateProductRequest request) {

        Product product = Product.builder()
                .sku(request.sku())
                .name(request.name())
                .description(request.description())
                .priceMinor(request.priceMinor())
                .currency(request.currency())
                .active(true)
                .saleStartsAt(request.saleStartsAt())
                .saleEndsAt(request.saleEndsAt())
                .createdAt(Instant.now())
                .build();

        Stock stock = Stock.builder()
                .product(product)
                .onHand(0L)
                .reserved(0L)
                .build();

        product.setStock(stock);

        return product;
    }

    public void updateProduct(Product product, UpdateProductRequest request) {

        if (request.name() != null) {
            product.setName(request.name());
        }

        if (request.priceMinor() != null) {
            product.setPriceMinor(request.priceMinor());
        }

        if (request.active() != null) {
            product.setActive(request.active());
        }

        if (request.saleStartsAt() != null) {
            product.setSaleStartsAt(request.saleStartsAt());
        }
        if (request.saleEndsAt() != null) {
            product.setSaleEndsAt(request.saleEndsAt());
        }

        product.setUpdatedAt(Instant.now());
    }
}
