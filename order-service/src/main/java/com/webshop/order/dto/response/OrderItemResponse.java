package com.webshop.order.dto.response;

import java.util.UUID;

public record OrderItemResponse(
        UUID productId,
        String productName,
        long unitPriceVnd,
        int quantity,
        long lineTotalVnd) {
}
