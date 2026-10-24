package com.webshop.inventory.dto.response;

import java.time.Instant;
import java.util.UUID;

/** available = stockOnHand - reservedQuantity: số lượng còn bán được. */
public record StockResponse(
        UUID productId,
        int stockOnHand,
        int reservedQuantity,
        int available,
        Instant updatedAt) {
}
