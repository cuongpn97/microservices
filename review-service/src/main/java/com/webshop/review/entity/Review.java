package com.webshop.review.entity;

import com.webshop.review.enums.ReviewStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/** Đánh giá của MỘT người cho MỘT sản phẩm (SPU). Viết lại = sửa bản cũ (unique product_id + user_id). */
@Entity
@Table(name = "review")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Review {

    @Id
    private UUID id;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "author_name", nullable = false)
    private String authorName;

    @Column(nullable = false)
    private short rating;

    private String title;

    @Column(nullable = false)
    private String content;

    @Column(name = "variant_name", nullable = false)
    private String variantName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public Review(UUID id, UUID productId, UUID userId, String authorName, String variantName, Instant now) {
        this.id = id;
        this.productId = productId;
        this.userId = userId;
        this.authorName = authorName;
        this.variantName = variantName;
        this.status = ReviewStatus.PUBLISHED;
        this.createdAt = now;
    }

    /** Sửa nội dung KHÔNG đổi trạng thái kiểm duyệt: bài đã bị ẩn không tự hiện lại khi người viết sửa. */
    public void edit(int rating, String title, String content, Instant now) {
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("rating must be between 1 and 5");
        }
        this.rating = (short) rating;
        this.title = title;
        this.content = content;
        this.updatedAt = now;
    }

    public void moderate(ReviewStatus status, Instant now) {
        this.status = status;
        this.updatedAt = now;
    }
}
