package com.webshop.inventory.dto;

import java.util.UUID;

/**
 * Một dòng cần giữ chỗ, đầu vào của InventoryService.reserve.
 * Hậu tố Dto để phân biệt với entity ReservationLine (dòng đã lưu DB).
 */
public record ReservationLineDto(UUID productId, int quantity) {
}
