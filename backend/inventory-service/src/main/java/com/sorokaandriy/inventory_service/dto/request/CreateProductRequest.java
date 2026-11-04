package com.sorokaandriy.inventory_service.dto.request;


import jakarta.validation.constraints.*;
import java.time.Instant;

public record CreateProductRequest(
    @NotBlank(message = "sku is required")
    String sku,

    @NotBlank(message = "name is required")
    @Size(max = 256, message = "name must not exceed 256 characters")
    String name,

    String description,

    @NotNull(message = "priceMinor is required")
    @Positive(message = "priceMinor must be greater than zero")
    Long priceMinor,

    @NotBlank(message = "currency is required")
    @Pattern(regexp = "UAH", message = "currency must be UAH")
    String currency,

    Instant saleStartsAt,

    Instant saleEndsAt
) {
}
