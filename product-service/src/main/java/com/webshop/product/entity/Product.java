package com.webshop.product.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Chỉ dùng @Getter, KHÔNG dùng @Data/@Setter cho entity:
 * - @Data sinh equals/hashCode theo mọi field → lỗi với Hibernate proxy và collection lazy.
 * - Không có setter → chỉ đổi được dữ liệu qua constructor/update(), luôn cập nhật updatedAt.
 */
@Entity
@Table(name = "products")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED) // JPA cần constructor rỗng, code ứng dụng thì không được dùng
public class Product {

    @Id
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String description;

    /** Giá theo VND, kiểu long (số nguyên). Không bao giờ dùng double/float cho tiền. */
    @Column(name = "price_vnd", nullable = false)
    private long priceVnd;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public Product(UUID id, String name, String description, long priceVnd, boolean active, Instant now) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.priceVnd = priceVnd;
        this.active = active;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void update(String name, String description, long priceVnd, boolean active, Instant now) {
        this.name = name;
        this.description = description;
        this.priceVnd = priceVnd;
        this.active = active;
        this.updatedAt = now;
    }
}
