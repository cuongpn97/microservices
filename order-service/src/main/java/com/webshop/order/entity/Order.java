package com.webshop.order.entity;

import com.webshop.order.enums.OrderStatus;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Đơn hàng phía khách. Trạng thái do Saga quyết định (CheckoutSagaOrchestrator gọi confirm/fail);
 * entity chỉ đảm bảo chuyển trạng thái hợp lệ (chỉ từ PENDING).
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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
    @Getter(AccessLevel.NONE) // tự viết getItems() bên dưới: trả bản sao đã sắp xếp, không lộ list nội bộ
    private List<OrderItem> items = new ArrayList<>();

    public Order(UUID id, UUID userId, String idempotencyKey, long cartRevision, List<OrderItem> items, Instant now) {
        if (items.isEmpty()) {
            throw new IllegalArgumentException("order must contain at least one item");
        }
        this.id = id;
        this.userId = userId;
        this.idempotencyKey = idempotencyKey;
        this.cartRevision = cartRevision;
        this.items = new ArrayList<>(items);
        this.totalVnd = items.stream().mapToLong(OrderItem::getLineTotalVnd).reduce(0L, Math::addExact);
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

    public List<OrderItem> getItems() {
        return items.stream().sorted(Comparator.comparing(OrderItem::getSkuId)).toList();
    }

    private void requirePending() {
        if (status != OrderStatus.PENDING) {
            throw new IllegalStateException("Order " + id + " is already " + status);
        }
    }
}
