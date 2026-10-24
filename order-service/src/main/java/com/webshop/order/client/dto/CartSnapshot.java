package com.webshop.order.client.dto;

import java.util.List;
import java.util.UUID;

/**
 * Phần response của GET /api/cart (Cart Service) mà Order cần; Jackson bỏ qua các field khác.
 * Không lấy giá từ giỏ: giá luôn đọc lại từ Product Service lúc checkout.
 *
 * @param revision phiên bản giỏ hàng — chặn checkout hai lần cùng một giỏ (unique index ux_orders_active_cart).
 */
public record CartSnapshot(UUID userId, long revision, List<Line> items) {

    public record Line(UUID productId, int quantity) {
    }
}
