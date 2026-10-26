package com.webshop.review.repository;

import com.webshop.review.entity.ProductRating;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ProductRatingRepository extends JpaRepository<ProductRating, UUID> {

    /** Tạo dòng tóm tắt rỗng nếu chưa có — an toàn khi hai review đầu tiên của sản phẩm đến cùng lúc. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "INSERT INTO product_rating (product_id, updated_at) VALUES (:productId, :now) "
            + "ON CONFLICT (product_id) DO NOTHING", nativeQuery = true)
    int createIfAbsent(@Param("productId") UUID productId, @Param("now") Instant now);

    /**
     * Khóa dòng tóm tắt TRƯỚC khi sửa review của sản phẩm: transaction thứ hai chờ, và (READ COMMITTED) lúc đếm lại
     * sẽ thấy review mà transaction thứ nhất đã commit → tổng không bao giờ bị ghi đè bởi số cũ.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ProductRating r where r.productId = :productId")
    Optional<ProductRating> lockById(@Param("productId") UUID productId);
}
