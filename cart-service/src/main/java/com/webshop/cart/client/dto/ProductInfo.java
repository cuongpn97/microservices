package com.webshop.cart.client.dto;

import java.util.UUID;

/** Phần response của Product Service (GET /api/products/{id}, /batch) mà Cart cần; Jackson bỏ qua field khác. */
public record ProductInfo(UUID id, String name, long priceVnd, boolean active) {
}
