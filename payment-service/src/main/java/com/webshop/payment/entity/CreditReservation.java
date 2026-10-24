package com.webshop.payment.entity;

import com.webshop.payment.enums.CreditReservationStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * Một dòng của bảng credit_reservation (id = orderId).
 * <p>
 * Service này dùng JDBC thuần, KHÔNG dùng JPA: các "entity" ở đây chỉ là record ánh xạ một dòng kết quả SQL,
 * không có @Entity, không tự lưu khi thay đổi. Mọi thay đổi đều qua câu SQL tường minh trong repository.
 *
 * @param walletId null khi user chưa có ví (UserRegistered chưa xử lý xong) hoặc là tombstone RELEASED.
 */
public record CreditReservation(
        UUID id,
        UUID userId,
        UUID walletId,
        long amountVnd,
        CreditReservationStatus status,
        String reason,
        Instant updatedAt) {
}
