package com.webshop.order.dto;

import com.webshop.order.dto.response.OrderResponse;

/**
 * Kết quả CheckoutService → OrderController.
 *
 * @param replayed true nếu Idempotency-Key đã dùng trước đó: trả lại đơn cũ (HTTP 200) thay vì tạo đơn mới (202).
 */
public record CheckoutResult(OrderResponse order, boolean replayed) {
}
