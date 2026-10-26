package com.webshop.inventory.entity;

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
 * Tồn kho của một SKU (biến thể bán được — id do Product Service cấp). Không có setter: mọi thay đổi số lượng đi qua reserve/commit/release/setStockOnHand,
 * mỗi method tự kiểm tra bất biến (không âm, không thấp hơn lượng đang giữ chỗ).
 */
@Entity
@Table(name = "inventory_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InventoryItem {

    @Id
    @Column(name = "sku_id")
    private UUID skuId;

    @Column(name = "stock_on_hand", nullable = false)
    private int stockOnHand;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public InventoryItem(UUID skuId, int stockOnHand, Instant now) {
        if (stockOnHand < 0) {
            throw new IllegalArgumentException("stockOnHand must be >= 0");
        }
        this.skuId = skuId;
        this.stockOnHand = stockOnHand;
        this.reservedQuantity = 0;
        this.updatedAt = now;
    }

    /** available = stock_on_hand - reserved_quantity */
    public int available() {
        return stockOnHand - reservedQuantity;
    }

    public boolean canReserve(int quantity) {
        return quantity > 0 && available() >= quantity;
    }

    /** Giữ chỗ: hàng vẫn trong kho nhưng không bán cho người khác được nữa. */
    public void reserve(int quantity, Instant now) {
        if (!canReserve(quantity)) {
            throw new IllegalStateException("Insufficient available stock for " + skuId);
        }
        reservedQuantity += quantity;
        updatedAt = now;
    }

    /** Commit: hàng chính thức rời kho — giảm cả tồn kho và lượng đang giữ. */
    public void commit(int quantity, Instant now) {
        if (quantity <= 0 || reservedQuantity < quantity || stockOnHand < quantity) {
            throw new IllegalStateException("Cannot commit " + quantity + " for " + skuId);
        }
        reservedQuantity -= quantity;
        stockOnHand -= quantity;
        updatedAt = now;
    }

    /** Release: trả lại lượng đang giữ cho phần available. */
    public void release(int quantity, Instant now) {
        if (quantity <= 0 || reservedQuantity < quantity) {
            throw new IllegalStateException("Cannot release " + quantity + " for " + skuId);
        }
        reservedQuantity -= quantity;
        updatedAt = now;
    }

    public void setStockOnHand(int newStock, Instant now) {
        if (newStock < reservedQuantity) {
            throw new IllegalStateException("stockOnHand cannot be lower than reservedQuantity (" + reservedQuantity + ")");
        }
        stockOnHand = newStock;
        updatedAt = now;
    }
}
