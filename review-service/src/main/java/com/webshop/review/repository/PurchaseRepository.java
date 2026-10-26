package com.webshop.review.repository;

import com.webshop.review.entity.Purchase;
import com.webshop.review.entity.PurchaseId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PurchaseRepository extends JpaRepository<Purchase, PurchaseId> {

    /** Idempotent: cùng (user, sản phẩm, đơn) ghi lại nhiều lần (event giao trùng, redrive) không lỗi, không nhân bản. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT INTO purchase (user_id, product_id, order_id, sku_id, variant_name, purchased_at)
            VALUES (:userId, :productId, :orderId, :skuId, :variantName, :purchasedAt)
            ON CONFLICT (user_id, product_id, order_id) DO NOTHING
            """, nativeQuery = true)
    int recordIfAbsent(@Param("userId") UUID userId, @Param("productId") UUID productId,
                       @Param("orderId") UUID orderId, @Param("skuId") UUID skuId,
                       @Param("variantName") String variantName, @Param("purchasedAt") Instant purchasedAt);

    /** Lần mua gần nhất — lấy tên biến thể ("Phân loại") gắn vào đánh giá. */
    Optional<Purchase> findFirstByUserIdAndProductIdOrderByPurchasedAtDesc(UUID userId, UUID productId);
}
