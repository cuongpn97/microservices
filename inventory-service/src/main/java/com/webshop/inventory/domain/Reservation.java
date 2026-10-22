package com.webshop.inventory.domain;

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
@Table(name = "reservation")
public class Reservation {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    private String reason;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "reservation_line", joinColumns = @JoinColumn(name = "reservation_id"))
    private List<Line> lines = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Reservation() {
    }

    public UUID getId() {
        return id;
    }

    public ReservationStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public List<Line> getLines() {
        return lines.stream().sorted(Comparator.comparing(Line::getProductId)).toList();
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void markReserved(List<Line> reservedLines, Instant now) {
        requireStatus(ReservationStatus.RESERVED);
        lines.clear();
        lines.addAll(reservedLines);
        updatedAt = now;
    }

    public void reject(String reason, Instant now) {
        requireStatus(ReservationStatus.RESERVED);
        this.status = ReservationStatus.REJECTED;
        this.reason = reason;
        updatedAt = now;
    }

    public void commit(Instant now) {
        requireStatus(ReservationStatus.RESERVED);
        status = ReservationStatus.COMMITTED;
        updatedAt = now;
    }

    public void release(String reason, Instant now) {
        requireStatus(ReservationStatus.RESERVED);
        status = ReservationStatus.RELEASED;
        this.reason = reason;
        updatedAt = now;
    }

    private void requireStatus(ReservationStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Reservation " + id + " is " + status + ", expected " + expected);
        }
    }

    @Embeddable
    public static class Line {
        @Column(name = "product_id", nullable = false)
        private UUID productId;

        @Column(nullable = false)
        private int quantity;

        protected Line() {
        }

        public Line(UUID productId, int quantity) {
            this.productId = productId;
            this.quantity = quantity;
        }

        public UUID getProductId() {
            return productId;
        }

        public int getQuantity() {
            return quantity;
        }
    }
}
