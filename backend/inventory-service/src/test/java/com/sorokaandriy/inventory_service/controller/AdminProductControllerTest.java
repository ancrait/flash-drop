package com.sorokaandriy.inventory_service.controller;

import com.sorokaandriy.inventory_service.dto.request.AdjustStockRequest;
import com.sorokaandriy.inventory_service.dto.request.CreateProductRequest;
import com.sorokaandriy.inventory_service.dto.request.UpdateProductRequest;
import com.sorokaandriy.inventory_service.dto.response.ProductResponse;
import com.sorokaandriy.inventory_service.dto.response.StockResponse;
import com.sorokaandriy.inventory_service.exception.handler.GlobalExceptionHandler;
import com.sorokaandriy.inventory_service.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;
import java.time.Instant;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class AdminProductControllerTest {
    @Mock
    private ProductService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = standaloneSetup(new AdminProductController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void createsProductAndReturns201() throws Exception {
        UUID id = UUID.randomUUID();
        CreateProductRequest request = new CreateProductRequest("SKU-1", "Keyboard", null, 15000L, "UAH", null, null);
        when(service.createProduct(request)).thenReturn(ProductResponse.builder().id(id).name("Keyboard").build());

        mvc.perform(post("/api/v1/admin/products").contentType(MediaType.APPLICATION_JSON).content("""
                {"sku":"SKU-1","name":"Keyboard","priceMinor":15000,"currency":"UAH"}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()));
        verify(service).createProduct(request);
    }

    @Test
    void acceptsSaleDatesWithTimezoneAndReturnsUtcInstants() throws Exception {
        Instant start = Instant.parse("2026-11-01T10:00:00Z");
        Instant end = start.plusSeconds(3600);
        CreateProductRequest request = new CreateProductRequest("SKU-1", "Keyboard", null, 15000L, "UAH", start, end);
        when(service.createProduct(request)).thenReturn(ProductResponse.builder()
                .saleStartsAt(start).saleEndsAt(end).build());

        mvc.perform(post("/api/v1/admin/products").contentType(MediaType.APPLICATION_JSON).content("""
                {"sku":"SKU-1","name":"Keyboard","priceMinor":15000,"currency":"UAH",
                 "saleStartsAt":"2026-11-01T12:00:00+02:00","saleEndsAt":"2026-11-01T13:00:00+02:00"}
                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.saleStartsAt").value("2026-11-01T10:00:00Z"))
                .andExpect(jsonPath("$.saleEndsAt").value("2026-11-01T11:00:00Z"));
        verify(service).createProduct(request);
    }

    @Test
    void invalidSaleWindowReturns400ProblemDetail() throws Exception {
        when(service.createProduct(any())).thenThrow(
                new com.sorokaandriy.inventory_service.exception.InvalidSaleWindowException(
                        "saleStartsAt must be before saleEndsAt"));

        mvc.perform(post("/api/v1/admin/products").contentType(MediaType.APPLICATION_JSON).content("""
                {"sku":"SKU-1","name":"Keyboard","priceMinor":15000,"currency":"UAH",
                 "saleStartsAt":"2026-11-01T10:00:00Z","saleEndsAt":"2026-11-01T10:00:00Z"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("saleStartsAt must be before saleEndsAt"));
    }

    @Test
    void patchesProductAndReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        UpdateProductRequest request = new UpdateProductRequest(null, null, false, null, null);
        when(service.updateProduct(id, request)).thenReturn(ProductResponse.builder().id(id).active(false).build());

        mvc.perform(patch("/api/v1/admin/products/{id}", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
        verify(service).updateProduct(id, request);
    }

    @Test
    void adjustsStockAndReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.stockAdjustment(id, new AdjustStockRequest(5L)))
                .thenReturn(StockResponse.builder().productId(id).onHand(15L).reserved(3L).available(12L).build());

        mvc.perform(post("/api/v1/admin/products/{id}/stock-adjustments", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(id.toString()))
                .andExpect(jsonPath("$.available").value(12));
        verify(service).stockAdjustment(id, new AdjustStockRequest(5L));
    }

    @Test
    void rejectsInvalidCreateRequestBeforeCallingService() throws Exception {
        mvc.perform(post("/api/v1/admin/products").contentType(MediaType.APPLICATION_JSON).content("""
                {"sku":"SKU-1","name":"Keyboard","priceMinor":0,"currency":"UAH"}
                """))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void rejectsInvalidPatchBeforeCallingService() throws Exception {
        mvc.perform(patch("/api/v1/admin/products/{id}", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\" \"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"quantity\":0}", "{\"quantity\":-1}"})
    void rejectsInvalidStockAdjustmentBeforeCallingService(String json) throws Exception {
        mvc.perform(post("/api/v1/admin/products/{id}/stock-adjustments", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    void rejectsMalformedJsonBeforeCallingService() throws Exception {
        mvc.perform(post("/api/v1/admin/products").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }
}
