package com.sorokaandriy.inventory_service.dto.request;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Null fields are left unchanged when applying PATCH. */
public record UpdateProductRequest(
        @Pattern(regexp = "(?s).*\\S.*", message = "name must not be blank")
        @Size(max = 256, message = "name must not exceed 256 characters")
        String name,

        @Positive(message = "priceMinor must be greater than zero")
        Long priceMinor,

        Boolean active,

        Instant saleStartsAt,

        Instant saleEndsAt
) {
}
