package com.webshop.cart.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "cart")
public class Cart {

    public static final int MAX_DISTINCT_ITEMS = 50;
    public static final int MAX_QUANTITY_PER_ITEM = 99;

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(nullable = false)
    private long revision;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "cart_item", joinColumns = @JoinColumn(name = "user_id"))
    @MapKeyColumn(name = "product_id")
    @Column(name = "quantity")
    private Map<UUID, Integer> items = new HashMap<>();

    protected Cart() {
    }

    public Cart(UUID userId, Instant now) {
        this.userId = userId;
        this.updatedAt = now;
    }

    public void add(UUID productId, int quantity, Instant now) {
        requirePositive(quantity);
        int newQuantity = items.getOrDefault(productId, 0) + quantity;
        set(productId, newQuantity, now);
    }

    public void set(UUID productId, int quantity, Instant now) {
        requirePositive(quantity);
        if (quantity > MAX_QUANTITY_PER_ITEM) {
            throw new IllegalArgumentException("quantity must be <= " + MAX_QUANTITY_PER_ITEM);
        }
        if (!items.containsKey(productId) && items.size() >= MAX_DISTINCT_ITEMS) {
            throw new IllegalArgumentException("cart cannot contain more than " + MAX_DISTINCT_ITEMS + " products");
        }
        items.put(productId, quantity);
        touch(now);
    }

    public void remove(UUID productId, Instant now) {
        if (items.remove(productId) != null) {
            touch(now);
        }
    }

    public void clear(Instant now) {
        if (!items.isEmpty()) {
            items.clear();
            touch(now);
        }
    }

    /**
     * Áp dụng đơn hàng đã CONFIRMED: trừ đúng số lượng đã mua. Sản phẩm/số lượng thêm vào SAU lúc checkout
     * vẫn được giữ lại (không xóa "mù" toàn bộ giỏ).
     */
    public void removePurchased(Map<UUID, Integer> purchased, Instant now) {
        boolean changed = false;
        for (Map.Entry<UUID, Integer> entry : purchased.entrySet()) {
            Integer current = items.get(entry.getKey());
            if (current == null) {
                continue;
            }
            int remaining = current - entry.getValue();
            if (remaining > 0) {
                items.put(entry.getKey(), remaining);
            } else {
                items.remove(entry.getKey());
            }
            changed = true;
        }
        if (changed) {
            touch(now);
        }
    }

    private void touch(Instant now) {
        revision++;
        updatedAt = now;
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
    }

    public UUID getUserId() {
        return userId;
    }

    public long getRevision() {
        return revision;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Map<UUID, Integer> getItems() {
        return Collections.unmodifiableMap(items);
    }
}
