package com.webshop.review.messaging.event;

import java.util.Map;
import java.util.UUID;

/**
 * Event "ProductRatingChanged" v1 — Product Service nghe để lưu bản sao điểm đánh giá (hiển thị + đẩy sang search).
 * Gửi TỔNG (count, sum) thay vì "+1 sao" để consumer chỉ cần giữ bản mới nhất: event lặp/đảo thứ tự không làm sai số.
 *
 * @param version tăng mỗi lần tóm tắt đổi — consumer bỏ qua bản có version không lớn hơn bản đang có.
 */
public record ProductRatingChangedEvent(UUID productId, int ratingCount, long ratingSum, Double ratingAvg,
                                        Map<Integer, Integer> histogram, long version) {
}
