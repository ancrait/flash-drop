package com.sorokaandriy.inventory_service.controller;

import com.sorokaandriy.inventory_service.dto.request.AdjustStockRequest;
import com.sorokaandriy.inventory_service.dto.request.CreateProductRequest;
import com.sorokaandriy.inventory_service.dto.request.UpdateProductRequest;
import com.sorokaandriy.inventory_service.dto.response.ProductResponse;
import com.sorokaandriy.inventory_service.dto.response.StockResponse;
import com.sorokaandriy.inventory_service.entity.Product;
import com.sorokaandriy.inventory_service.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("api/v1/admin/products")
@RequiredArgsConstructor
public class AdminProductController {

    private final ProductService service;

    @PostMapping
    public ResponseEntity<ProductResponse> createProduct(
            @Valid @RequestBody CreateProductRequest request
            ){

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.createProduct(request));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateProductRequest request
            ){
        return ResponseEntity.status(HttpStatusCode.valueOf(200))
                .body(service.updateProduct(id, request));
    }

    @PostMapping("/{id}/stock-adjustments")
    public ResponseEntity<StockResponse> stockAdjustment(
            @PathVariable UUID id,
            @Valid @RequestBody AdjustStockRequest request
            ){
        return ResponseEntity.status(HttpStatusCode.valueOf(200))
                .body(service.stockAdjustment(id, request));
    }
}
