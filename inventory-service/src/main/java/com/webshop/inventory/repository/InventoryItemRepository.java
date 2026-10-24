package com.webshop.inventory.repository;

import com.webshop.inventory.entity.InventoryItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, UUID> {

    /**
     * Khóa bi quan (SELECT ... FOR UPDATE) các dòng tồn kho THEO THỨ TỰ productId.
     * Thứ tự khóa cố định giữa mọi transaction → tránh deadlock khi hai đơn cùng chứa A và B
     * nhưng liệt kê theo thứ tự khác nhau.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InventoryItem i where i.productId in :ids order by i.productId")
    List<InventoryItem> lockAllOrdered(@Param("ids") Collection<UUID> ids);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InventoryItem i where i.productId = :id")
    Optional<InventoryItem> lockById(@Param("id") UUID id);
}
