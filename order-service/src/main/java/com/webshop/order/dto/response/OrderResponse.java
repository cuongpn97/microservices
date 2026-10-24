package com.webshop.order.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Đơn hàng kèm trạng thái Saga — client poll endpoint này sau checkout.
 *
 * @param status        trạng thái phía khách: PENDING / CONFIRMED / FAILED.
 * @param sagaState     trạng thái chi tiết của Saga (PENDING, INVENTORY_RESERVED, CONFIRMING, ...); null nếu chưa có.
 * @param failureReason ví dụ "CREDIT_REJECTED:INSUFFICIENT_FUNDS"; null nếu chưa thất bại.
 */
public record OrderResponse(
        UUID orderId,
        String status,
        String sagaState,
        long totalVnd,
        List<OrderItemResponse> items,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {
}
