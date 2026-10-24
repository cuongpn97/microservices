package com.webshop.order.client.dto;

import java.util.UUID;

/** Phần response của GET /api/products/batch (Product Service) mà Order cần: giá có thẩm quyền + trạng thái. */
public record ProductInfo(UUID id, String name, long priceVnd, boolean active) {
}
