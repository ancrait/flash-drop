package com.sorokaandriy.inventory_service.controller;

import com.sorokaandriy.inventory_service.dto.response.ProductResponse;
import com.sorokaandriy.inventory_service.exception.handler.GlobalExceptionHandler;
import com.sorokaandriy.inventory_service.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class ProductControllerTest {
    @Mock
    private ProductService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = standaloneSetup(new ProductController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver()).build();
    }

    @Test
    void getsProductById() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getProduct(id)).thenReturn(ProductResponse.builder().id(id).name("Keyboard").build());

        mvc.perform(get("/api/v1/products/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.name").value("Keyboard"));
        verify(service).getProduct(id);
    }

    @Test
    void appliesDefaultCatalogPagination() throws Exception {
        when(service.getProducts(any(Pageable.class))).thenAnswer(invocation -> Page.empty(invocation.getArgument(0)));

        mvc.perform(get("/api/v1/products")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(service).getProducts(captor.capture());
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(20);
        assertThat(captor.getValue().getSort()).isEqualTo(Sort.by("name"));
    }

    @Test
    void passesExplicitPaginationAndSortingToService() throws Exception {
        when(service.getProducts(any(Pageable.class))).thenAnswer(invocation -> Page.empty(invocation.getArgument(0)));

        mvc.perform(get("/api/v1/products").param("page", "2").param("size", "5").param("sort", "priceMinor,desc"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(service).getProducts(captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(2);
        assertThat(captor.getValue().getPageSize()).isEqualTo(5);
        assertThat(captor.getValue().getSort()).isEqualTo(Sort.by(Sort.Direction.DESC, "priceMinor"));
    }

    @Test
    void rejectsMalformedProductIdBeforeCallingService() throws Exception {
        mvc.perform(get("/api/v1/products/not-a-uuid")).andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }
}
