package com.webshop.order.saga;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CheckoutSagaRepository extends JpaRepository<CheckoutSaga, UUID> {

    /** Khóa Saga khi áp dụng reply: hai reply đồng thời (vd. commit + capture) được xử lý tuần tự. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CheckoutSaga s where s.id = :id")
    Optional<CheckoutSaga> lockById(@Param("id") UUID id);

    List<CheckoutSaga> findByNeedsAttentionTrueOrderByUpdatedAtDesc();

    long countByStateIn(List<SagaState> states);
}
