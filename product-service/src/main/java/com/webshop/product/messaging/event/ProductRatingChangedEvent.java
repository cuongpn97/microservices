package com.webshop.product.messaging.event;

import java.util.UUID;

/**
 * Payload event "ProductRatingChanged" v1 do review-service phát. Review còn gửi histogram nhưng Product chỉ cần
 * tổng → không khai báo (tolerant reader).
 *
 * @param version tăng mỗi lần tóm tắt của sản phẩm đổi — Product bỏ qua bản cũ đến muộn.
 */
public record ProductRatingChangedEvent(UUID productId, int ratingCount, long ratingSum, long version) {
}
