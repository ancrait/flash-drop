package com.sorokaandriy.inventory_service.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.util.List;
import java.util.UUID;

/** Input for inventory reservation; request hash and status are assigned by the service. */
public record CreateReservationRequest(
        @NotNull(message = "orderId is required")
        UUID orderId,

        @NotBlank(message = "currency is required")
        @Pattern(regexp = "UAH", message = "currency must be UAH")
        String currency,

        @NotEmpty(message = "items must contain at least one item")
        List<@NotNull(message = "item must not be null") @Valid ReservationItemRequest> items
) {
}
