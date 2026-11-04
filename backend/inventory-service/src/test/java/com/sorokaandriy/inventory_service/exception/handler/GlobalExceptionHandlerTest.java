package com.sorokaandriy.inventory_service.exception.handler;

import com.sorokaandriy.inventory_service.controller.AdminProductController;
import com.sorokaandriy.inventory_service.controller.ProductController;
import com.sorokaandriy.inventory_service.dto.request.CreateProductRequest;
import com.sorokaandriy.inventory_service.exception.ProductNotFoundException;
import com.sorokaandriy.inventory_service.service.ProductService;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

@ExtendWith(MockitoExtension.class)
class GlobalExceptionHandlerTest {
    private static final String PRODUCTS = "/api/v1/products";
    private static final String ADMIN_PRODUCTS = "/api/v1/admin/products";
    private static final String VALID_PRODUCT = """
            {"sku":"SKU-1","name":"Keyboard","priceMinor":100,"currency":"UAH"}
            """;

    @Mock
    private ProductService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = standaloneSetup(new ProductController(service), new AdminProductController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new PageableHandlerMethodArgumentResolver())
                .build();
    }

    @Test
    void returnsProblemDetailsForMissingProduct() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getProduct(id)).thenThrow(new ProductNotFoundException("Product not found"));

        mvc.perform(get(PRODUCTS + "/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.detail").value("Product not found"))
                .andExpect(jsonPath("$.instance").value(PRODUCTS + "/" + id));
    }

    @Test
    void exposesValidationMessagesByFieldAsProblemExtensions() throws Exception {
        mvc.perform(post(ADMIN_PRODUCTS).contentType(MediaType.APPLICATION_JSON).content("""
                {"sku":"","name":"","priceMinor":0,"currency":"USD"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Validation failed"))
                .andExpect(jsonPath("$.errors.sku", hasItem("sku is required")))
                .andExpect(jsonPath("$.errors.name", hasItem("name is required")))
                .andExpect(jsonPath("$.errors.priceMinor", hasItem("priceMinor must be greater than zero")))
                .andExpect(jsonPath("$.errors.currency", hasItem("currency must be UAH")));
        verifyNoInteractions(service);
    }

    @Test
    void malformedJsonReturns400WithoutParserDetails() throws Exception {
        mvc.perform(post(ADMIN_PRODUCTS).contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Request body is missing or contains invalid JSON"));
        verifyNoInteractions(service);
    }

    @Test
    void missingBodyReturns400() throws Exception {
        mvc.perform(post(ADMIN_PRODUCTS).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void invalidUuidKeeps400() throws Exception {
        mvc.perform(get(PRODUCTS + "/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
        verifyNoInteractions(service);
    }

    @Test
    void unsupportedHttpMethodKeeps405AndAllowHeader() throws Exception {
        mvc.perform(delete(PRODUCTS + "/{id}", UUID.randomUUID()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.status").value(405));
    }

    @Test
    void unsupportedContentTypeKeeps415() throws Exception {
        mvc.perform(post(ADMIN_PRODUCTS).contentType(MediaType.TEXT_PLAIN).content(VALID_PRODUCT))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.status").value(415));
    }

    @Test
    void duplicateSkuReturns409WithoutDatabaseDetails() throws Exception {
        ConstraintViolationException constraint = mock(ConstraintViolationException.class);
        when(constraint.getConstraintName()).thenReturn("uq_products_sku");
        when(service.createProduct(any(CreateProductRequest.class)))
                .thenThrow(new DataIntegrityViolationException("SQL with internal data", constraint));

        mvc.perform(post(ADMIN_PRODUCTS).contentType(MediaType.APPLICATION_JSON).content(VALID_PRODUCT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("A product with this SKU already exists"));
    }

    @Test
    void unrelatedConstraintFailureReturnsSafe500InsteadOfDuplicateSku() throws Exception {
        ConstraintViolationException constraint = mock(ConstraintViolationException.class);
        when(constraint.getConstraintName()).thenReturn("chk_stocks_available");
        when(service.createProduct(any(CreateProductRequest.class)))
                .thenThrow(new DataIntegrityViolationException("SQL with internal data", constraint));

        mvc.perform(post(ADMIN_PRODUCTS).contentType(MediaType.APPLICATION_JSON).content(VALID_PRODUCT))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected server error occurred"));
    }

    @Test
    void optimisticLockConflictReturns409() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.updateProduct(eq(id), any()))
                .thenThrow(new ObjectOptimisticLockingFailureException("Product", id));

        mvc.perform(patch(ADMIN_PRODUCTS + "/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void unexpectedFailureDoesNotExposeExceptionMessage() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getProduct(id)).thenThrow(new IllegalStateException("Internal database credentials"));

        mvc.perform(get(PRODUCTS + "/{id}", id))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.detail").value("An unexpected server error occurred"));
    }
}
