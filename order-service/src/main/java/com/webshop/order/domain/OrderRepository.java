package com.webshop.order.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    Optional<Order> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    List<Order> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);
}
