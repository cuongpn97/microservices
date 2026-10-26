package com.webshop.review.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Bằng chứng "đã mua": một dòng cho mỗi (user, sản phẩm, đơn hàng) đã CONFIRMED. Chỉ được ghi từ event OrderConfirmed
 * (PurchaseRepository.recordIfAbsent) — không có API nào cho client tự khai báo đã mua.
 */
@Entity
@Table(name = "purchase")
@IdClass(PurchaseId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Purchase {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @Id
    @Column(name = "order_id")
    private UUID orderId;

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    @Column(name = "variant_name", nullable = false)
    private String variantName;

    @Column(name = "purchased_at", nullable = false)
    private Instant purchasedAt;
}
