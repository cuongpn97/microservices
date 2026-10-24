package com.webshop.order.repository;

import com.webshop.order.entity.SagaTransition;
import com.webshop.order.enums.SagaState;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * Nhật ký chuyển trạng thái Saga — chỉ INSERT và đọc lại để debug/đối soát.
 * Dùng JdbcTemplate thay vì JPA: bảng append-only, id BIGSERIAL, không cần dirty checking hay quản lý entity.
 */
@Repository
@RequiredArgsConstructor
public class SagaTransitionRepository {

    private static final int MAX_DETAIL_LENGTH = 300;

    private final JdbcTemplate jdbcTemplate;

    public void append(UUID sagaId, SagaTransition transition) {
        String detail = transition.detail();
        jdbcTemplate.update("INSERT INTO saga_transition (saga_id, from_state, to_state, trigger, detail, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)", sagaId,
                transition.fromState() == null ? null : transition.fromState().name(), transition.toState().name(),
                transition.trigger(),
                detail == null ? null : detail.length() > MAX_DETAIL_LENGTH ? detail.substring(0, MAX_DETAIL_LENGTH) : detail,
                Timestamp.from(transition.createdAt()));
    }

    /** Theo thứ tự xảy ra. */
    public List<SagaTransition> findBySagaId(UUID sagaId) {
        return jdbcTemplate.query("SELECT from_state, to_state, trigger, detail, created_at FROM saga_transition "
                        + "WHERE saga_id = ? ORDER BY id",
                (rs, i) -> new SagaTransition(
                        rs.getString("from_state") == null ? null : SagaState.valueOf(rs.getString("from_state")),
                        SagaState.valueOf(rs.getString("to_state")),
                        rs.getString("trigger"),
                        rs.getString("detail"),
                        rs.getTimestamp("created_at").toInstant()),
                sagaId);
    }
}
