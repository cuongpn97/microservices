package com.webshop.order.repository;

import com.webshop.order.entity.CheckoutSaga;
import com.webshop.order.enums.SagaState;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CheckoutSagaRepository extends JpaRepository<CheckoutSaga, UUID> {

    /** Khóa Saga khi áp dụng reply: hai reply đồng thời (vd. commit + capture) được xử lý tuần tự. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CheckoutSaga s where s.id = :id")
    Optional<CheckoutSaga> lockById(@Param("id") UUID id);

    /**
     * Khóa tối đa 20 Saga chưa kết thúc đã quá deadline, cũ nhất trước (dùng bởi SagaTimeoutWatcher).
     * SKIP LOCKED: dòng đang bị instance khác khóa thì bỏ qua thay vì chờ → nhiều instance quét song song an toàn,
     * mỗi Saga chỉ một instance xử lý. Native query vì JPQL không có SKIP LOCKED.
     */
    @Query(value = """
            SELECT id FROM checkout_saga
            WHERE state IN ('PENDING', 'INVENTORY_RESERVED', 'CONFIRMING', 'COMPENSATING')
              AND deadline_at <= :now
            ORDER BY deadline_at
            LIMIT 20
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> lockDueIds(@Param("now") Instant now);

    List<CheckoutSaga> findByNeedsAttentionTrueOrderByUpdatedAtDesc();

    long countByStateIn(Collection<SagaState> states);

    long countByNeedsAttentionTrue();
}
