package com.sorokaandriy.inventory_service.controller;

import com.sorokaandriy.inventory_service.dto.response.ProductResponse;
import com.sorokaandriy.inventory_service.service.ProductService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService service;

    @GetMapping("/{id}")
    public ResponseEntity<ProductResponse> getProduct(
            @PathVariable UUID id
            ){
        return ResponseEntity.ok(service.getProduct(id));
    }


    @GetMapping
    public Page<ProductResponse> getProducts(
            @PageableDefault(size = 20, sort = "name") Pageable pageable
    ){
        return service.getProducts(pageable);
    }




}
