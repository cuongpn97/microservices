package com.webshop.inventory.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record SetStockRequest(@NotNull @PositiveOrZero @Max(1_000_000) Integer stockOnHand) {
}
