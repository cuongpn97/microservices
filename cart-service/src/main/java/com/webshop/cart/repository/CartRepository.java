package com.webshop.cart.repository;

import com.webshop.cart.entity.Cart;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface CartRepository extends JpaRepository<Cart, UUID> {

    /** Tạo giỏ rỗng nếu chưa có — an toàn khi hai request đầu tiên của user chạy song song. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "INSERT INTO cart (user_id, revision, updated_at) VALUES (:userId, 0, :now) "
            + "ON CONFLICT (user_id) DO NOTHING", nativeQuery = true)
    int createIfAbsent(@Param("userId") UUID userId, @Param("now") Instant now);

    /** Khóa giỏ khi sửa: hai request song song của cùng user không ghi đè lẫn nhau. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Cart c where c.userId = :userId")
    Optional<Cart> lockByUserId(@Param("userId") UUID userId);
}
