package com.webshop.order.messaging.event;

import java.util.List;
import java.util.UUID;

/**
 * Event "OrderConfirmed" v2 (v2: dòng hàng theo SKU, kèm SPU và tên biến thể).
 * Cart Service nghe để xóa các món đã mua khỏi giỏ; Review Service nghe để biết ai đã mua sản phẩm nào
 * (chỉ người đã mua mới được đánh giá).
 */
public record OrderConfirmedEvent(UUID orderId, UUID userId, long totalVnd, List<Item> items) {

    public static final int VERSION = 2;

    public record Item(UUID skuId, UUID productId, String productName, String variantName, int quantity) {
    }
}
