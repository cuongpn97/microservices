package com.webshop.product.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Metadata của một ảnh sản phẩm; file ảnh nằm trong object storage (S3) dưới {@link #objectKey}.
 * Chỉ tạo/sửa qua {@link Product} (aggregate root).
 */
@Entity
@Table(name = "product_image")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductImage {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    @Getter(AccessLevel.NONE)
    private Product product;

    @Column(name = "object_key", nullable = false)
    private String objectKey;

    @Column(name = "content_type", nullable = false)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "alt_text", nullable = false)
    private String altText;

    @Column(nullable = false)
    private int position;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    ProductImage(UUID id, Product product, String objectKey, String contentType, long sizeBytes, String altText,
                 int position, Instant now) {
        this.id = id;
        this.product = product;
        this.objectKey = objectKey;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.altText = altText;
        this.position = position;
        this.createdAt = now;
    }

    void changeAltText(String altText) {
        this.altText = altText;
    }

    void moveTo(int position) {
        this.position = position;
    }
}
