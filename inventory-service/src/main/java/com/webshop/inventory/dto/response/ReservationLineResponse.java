package com.webshop.inventory.dto.response;

import java.util.UUID;

public record ReservationLineResponse(UUID skuId, int quantity) {
}
