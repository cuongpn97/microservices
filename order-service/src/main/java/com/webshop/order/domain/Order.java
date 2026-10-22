package com.webshop.order.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embeddable;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @Column(name = "total_vnd", nullable = false)
    private long totalVnd;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "cart_revision", nullable = false)
    private long cartRevision;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    /** Snapshot bất biến; trigger DB cấm UPDATE/DELETE trên order_item. */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "order_item", joinColumns = @JoinColumn(name = "order_id"))
    private List<Item> items = new ArrayList<>();

    protected Order() {
    }

    public Order(UUID id, UUID userId, String idempotencyKey, long cartRevision, List<Item> items, Instant now) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("order must contain at least one item");
        }
        this.id = id;
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.cartRevision = cartRevision;
        this.items = new ArrayList<>(items);
        this.totalVnd = items.stream().mapToLong(Item::getLineTotalVnd).reduce(0L, Math::addExact);
        this.status = OrderStatus.PENDING;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void confirm(Instant now) {
        requirePending();
        status = OrderStatus.CONFIRMED;
        updatedAt = now;
    }

    public void fail(String reason, Instant now) {
        requirePending();
        status = OrderStatus.FAILED;
        failureReason = reason;
        updatedAt = now;
    }

    private void requirePending() {
        if (status != OrderStatus.PENDING) {
            throw new IllegalStateException("Order " + id + " is already " + status);
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public long getTotalVnd() {
        return totalVnd;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public long getCartRevision() {
        return cartRevision;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<Item> getItems() {
        return items.stream().sorted(Comparator.comparing(Item::getProductId)).toList();
    }

    @Embeddable
    public static class Item {
        @Column(name = "product_id", nullable = false)
        private UUID productId;

        @Column(name = "product_name", nullable = false)
        private String productName;

        @Column(name = "unit_price_vnd", nullable = false)
        private long unitPriceVnd;

        @Column(nullable = false)
        private int quantity;

        @Column(name = "line_total_vnd", nullable = false)
        private long lineTotalVnd;

        protected Item() {
        }

        public Item(UUID productId, String productName, long unitPriceVnd, int quantity) {
            if (quantity <= 0 || unitPriceVnd < 0) {
                throw new IllegalArgumentException("invalid order line");
            }
            this.productId = productId;
            this.productName = productName;
            this.unitPriceVnd = unitPriceVnd;
            this.quantity = quantity;
            this.lineTotalVnd = Math.multiplyExact(unitPriceVnd, (long) quantity);
        }

        public UUID getProductId() {
            return productId;
        }

        public String getProductName() {
            return productName;
        }

        public long getUnitPriceVnd() {
            return unitPriceVnd;
        }

        public int getQuantity() {
            return quantity;
        }

        public long getLineTotalVnd() {
            return lineTotalVnd;
        }
    }
}
