package com.webshop.order.service;

import com.webshop.order.dto.response.OrderResponse;

import java.util.List;
import java.util.UUID;

public interface OrderService {

    /** Đơn không tồn tại HOẶC thuộc user khác → cùng một lỗi 404 (không tiết lộ đơn của người khác có tồn tại). */
    OrderResponse getOrder(UUID userId, UUID orderId);

    /** 50 đơn mới nhất của user. */
    List<OrderResponse> getMyOrders(UUID userId);
}
