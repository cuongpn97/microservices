package com.webshop.inventory.entity;

import com.webshop.inventory.enums.ReservationStatus;
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
 * Giữ chỗ hàng cho một đơn (id = orderId). Dòng được tạo bằng ReservationRepository.insertIfAbsent
 * (native INSERT ... ON CONFLICT) nên entity không có constructor public.
 * Chuyển trạng thái hợp lệ: xem {@link ReservationStatus}.
 */
@Entity
@Table(name = "reservation")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;

    private String reason;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "reservation_line", joinColumns = @JoinColumn(name = "reservation_id"))
    @Getter(AccessLevel.NONE) // tự viết getLines() bên dưới: trả bản sao đã sắp xếp, không lộ list nội bộ
    private List<ReservationLine> lines = new ArrayList<>();

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    /** Sắp theo skuId để thứ tự khóa dòng tồn kho luôn cố định (tránh deadlock). */
    public List<ReservationLine> getLines() {
        return lines.stream().sorted(Comparator.comparing(ReservationLine::getSkuId)).toList();
    }

    public void markReserved(List<ReservationLine> reservedLines, Instant now) {
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
}
