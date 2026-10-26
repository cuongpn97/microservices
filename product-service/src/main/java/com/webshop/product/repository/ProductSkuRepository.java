package com.webshop.product.repository;

import com.webshop.product.entity.ProductSku;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProductSkuRepository extends JpaRepository<ProductSku, UUID> {

    /** Nạp kèm SPU trong một query: Cart/Order gọi batch theo skuId, cần tên SPU và cờ active của SPU. */
    @Query("select s from ProductSku s join fetch s.product where s.id in :ids")
    List<ProductSku> findAllWithProduct(@Param("ids") Collection<UUID> ids);

    boolean existsBySkuCode(String skuCode);

    boolean existsBySkuCodeAndIdNot(String skuCode, UUID id);
}
