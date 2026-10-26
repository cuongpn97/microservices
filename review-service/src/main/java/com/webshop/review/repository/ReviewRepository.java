package com.webshop.review.repository;

import com.webshop.review.entity.Review;
import com.webshop.review.enums.ReviewStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, UUID> {

    Optional<Review> findByProductIdAndUserId(UUID productId, UUID userId);

    @Query("select r.productId from Review r where r.id = :id")
    Optional<UUID> findProductIdById(@Param("id") UUID id);

    /** @param rating null = mọi mức sao. */
    @Query("""
            select r from Review r
            where r.productId = :productId and r.status = :status and (:rating is null or r.rating = :rating)
            """)
    Page<Review> findForProduct(@Param("productId") UUID productId, @Param("status") ReviewStatus status,
                                @Param("rating") Short rating, Pageable pageable);

    /** Danh sách kiểm duyệt: lọc tùy chọn theo trạng thái và sản phẩm. */
    @Query("""
            select r from Review r
            where (:status is null or r.status = :status) and (:productId is null or r.productId = :productId)
            """)
    Page<Review> search(@Param("status") ReviewStatus status, @Param("productId") UUID productId, Pageable pageable);

    /** Mỗi phần tử: [số sao (Short), số review (Long)] — chỉ review PUBLISHED. */
    @Query("""
            select r.rating, count(r) from Review r
            where r.productId = :productId and r.status = com.webshop.review.enums.ReviewStatus.PUBLISHED
            group by r.rating
            """)
    List<Object[]> countPublishedByStars(@Param("productId") UUID productId);
}
