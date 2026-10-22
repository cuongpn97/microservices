package com.webshop.inventory.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    /**
     * "Claim" reservationId một cách nguyên tử. Hai command trùng (khác messageId, cùng reservationId) chạy
     * song song: lệnh thứ hai chờ lệnh đầu commit rồi nhận 0 dòng → coi như bản trùng. Đây là chốt chặn
     * idempotency ở mức nghiệp vụ, bổ sung cho Inbox (vốn chỉ chặn trùng theo messageId).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO reservation (id, status, reason, created_at, updated_at, version)
            VALUES (:id, :status, :reason, :now, :now, 0)
            ON CONFLICT (id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("id") UUID id, @Param("status") String status, @Param("reason") String reason,
                       @Param("now") Instant now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Reservation r where r.id = :id")
    Optional<Reservation> lockById(@Param("id") UUID id);
}
