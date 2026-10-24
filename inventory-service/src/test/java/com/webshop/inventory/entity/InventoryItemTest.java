package com.webshop.inventory.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InventoryItemTest {

    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void availableIsStockOnHandMinusReserved() {
        InventoryItem item = new InventoryItem(UUID.randomUUID(), 10, now);
        item.reserve(3, now);
        assertThat(item.getStockOnHand()).isEqualTo(10);
        assertThat(item.getReservedQuantity()).isEqualTo(3);
        assertThat(item.available()).isEqualTo(7);
    }

    @Test
    void cannotReserveMoreThanAvailableOrNonPositive() {
        InventoryItem item = new InventoryItem(UUID.randomUUID(), 5, now);
        item.reserve(4, now);
        assertThat(item.canReserve(2)).isFalse();
        assertThat(item.canReserve(0)).isFalse();
        assertThatThrownBy(() -> item.reserve(2, now)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void commitRemovesStockAndReservation() {
        InventoryItem item = new InventoryItem(UUID.randomUUID(), 5, now);
        item.reserve(2, now);
        item.commit(2, now);
        assertThat(item.getStockOnHand()).isEqualTo(3);
        assertThat(item.getReservedQuantity()).isZero();
        assertThat(item.available()).isEqualTo(3);
    }

    @Test
    void releaseReturnsReservationToAvailable() {
        InventoryItem item = new InventoryItem(UUID.randomUUID(), 5, now);
        item.reserve(2, now);
        item.release(2, now);
        assertThat(item.available()).isEqualTo(5);
        assertThatThrownBy(() -> item.release(1, now)).as("không release quá lượng đang giữ")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stockCannotBeSetBelowReservedQuantity() {
        InventoryItem item = new InventoryItem(UUID.randomUUID(), 5, now);
        item.reserve(4, now);
        assertThatThrownBy(() -> item.setStockOnHand(3, now)).isInstanceOf(IllegalStateException.class);
        item.setStockOnHand(4, now);
        assertThat(item.available()).isZero();
    }
}
