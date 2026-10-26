package com.webshop.product.repository;

import com.webshop.product.entity.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface ProductRepository extends JpaRepository<Product, UUID> {

    Page<Product> findByActiveTrue(Pageable pageable);

    Optional<Product> findBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, UUID id);

    /**
     * Danh sách quản trị: kể cả sản phẩm tắt; q (đã lowercase) khớp tên hoặc slug, chuỗi rỗng = tất cả.
     * KHÔNG dùng "(:q is null or ... like ...)": khi q = null, Hibernate không suy ra được kiểu tham số trong
     * concat(...) và gửi null dạng bytea → PostgreSQL báo "operator does not exist: text ~~ bytea".
     */
    @Query("""
            select p from Product p
            where (lower(p.name) like concat('%', :q, '%') or p.slug like concat('%', :q, '%'))
              and (:active is null or p.active = :active)
            """)
    Page<Product> search(@Param("q") String q, @Param("active") Boolean active, Pageable pageable);

    @Query("select count(i) from ProductImage i where i.product.id = :id")
    long countImages(@Param("id") UUID id);

    /**
     * Khóa dòng SPU khi sửa bất cứ phần nào của aggregate (SPU, SKU, ảnh, rating): các thay đổi trên cùng sản phẩm
     * chạy tuần tự → catalog_document được ghi theo đúng thứ tự commit.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> lockById(@Param("id") UUID id);
}
