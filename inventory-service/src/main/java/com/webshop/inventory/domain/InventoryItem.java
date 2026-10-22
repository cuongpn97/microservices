package com.webshop.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inventory_item")
public class InventoryItem {

    @Id
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "stock_on_hand", nullable = false)
    private int stockOnHand;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected InventoryItem() {
    }

    public InventoryItem(UUID productId, int stockOnHand, Instant now) {
        if (stockOnHand < 0) {
            throw new IllegalArgumentException("stockOnHand must be >= 0");
        }
        this.productId = productId;
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
            throw new IllegalStateException("Insufficient available stock for " + productId);
        }
        reservedQuantity += quantity;
        updatedAt = now;
    }

    /** Commit: hàng chính thức rời kho — giảm cả tồn kho và lượng đang giữ. */
    public void commit(int quantity, Instant now) {
        if (quantity <= 0 || reservedQuantity < quantity || stockOnHand < quantity) {
            throw new IllegalStateException("Cannot commit " + quantity + " for " + productId);
        }
        reservedQuantity -= quantity;
        stockOnHand -= quantity;
        updatedAt = now;
    }

    /** Release: trả lại lượng đang giữ cho phần available. */
    public void release(int quantity, Instant now) {
        if (quantity <= 0 || reservedQuantity < quantity) {
            throw new IllegalStateException("Cannot release " + quantity + " for " + productId);
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

    public UUID getProductId() {
        return productId;
    }

    public int getStockOnHand() {
        return stockOnHand;
    }

    public int getReservedQuantity() {
        return reservedQuantity;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
