package com.webshop.review.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tóm tắt đánh giá của một sản phẩm (chỉ review PUBLISHED). Dòng được tạo bằng
 * ProductRatingRepository.createIfAbsent và khóa trước MỌI thay đổi review của sản phẩm đó.
 *
 * <p>{@link #revision} là bộ đếm tự quản (không phải @Version JPA): tăng mỗi lần tính lại, gửi kèm event
 * ProductRatingChanged để Product Service bỏ qua bản cũ đến muộn.</p>
 */
@Entity
@Table(name = "product_rating")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductRating {

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "rating_count", nullable = false)
    private int ratingCount;

    @Column(name = "rating_sum", nullable = false)
    private long ratingSum;

    @Column(name = "count_1", nullable = false)
    private int count1;

    @Column(name = "count_2", nullable = false)
    private int count2;

    @Column(name = "count_3", nullable = false)
    private int count3;

    @Column(name = "count_4", nullable = false)
    private int count4;

    @Column(name = "count_5", nullable = false)
    private int count5;

    @Column(nullable = false)
    private long revision;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Ghi lại toàn bộ từ số đếm theo từng mức sao (tính lại từ bảng review, không cộng dồn → không lệch theo thời gian). */
    public void replace(Map<Integer, Integer> countsByStars, Instant now) {
        count1 = countsByStars.getOrDefault(1, 0);
        count2 = countsByStars.getOrDefault(2, 0);
        count3 = countsByStars.getOrDefault(3, 0);
        count4 = countsByStars.getOrDefault(4, 0);
        count5 = countsByStars.getOrDefault(5, 0);
        ratingCount = count1 + count2 + count3 + count4 + count5;
        ratingSum = count1 + 2L * count2 + 3L * count3 + 4L * count4 + 5L * count5;
        revision++;
        updatedAt = now;
    }

    /** Điểm trung bình làm tròn 1 chữ số; null khi chưa có đánh giá. */
    public Double getAverage() {
        return ratingCount == 0 ? null : Math.round(ratingSum * 10.0 / ratingCount) / 10.0;
    }

    /** Số đánh giá theo số sao, từ 5 xuống 1 (thứ tự hiển thị). */
    public Map<Integer, Integer> getHistogram() {
        Map<Integer, Integer> histogram = new LinkedHashMap<>();
        histogram.put(5, count5);
        histogram.put(4, count4);
        histogram.put(3, count3);
        histogram.put(2, count2);
        histogram.put(1, count1);
        return histogram;
    }
}
