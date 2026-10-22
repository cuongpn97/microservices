package com.webshop.order.web;

import com.webshop.common.web.ApiException;
import com.webshop.order.saga.CheckoutSaga;
import com.webshop.order.saga.CheckoutSagaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Công cụ vận hành Saga (ROLE_ADMIN, gọi trực tiếp port service — gateway không route /admin). */
@RestController
@RequestMapping("/admin/sagas")
public class SagaAdminController {

    private final CheckoutSagaRepository sagas;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SagaAdminController(CheckoutSagaRepository sagas, JdbcTemplate jdbc, Clock clock) {
        this.sagas = sagas;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @GetMapping("/{sagaId}")
    @Transactional(readOnly = true)
    public SagaView get(@PathVariable UUID sagaId) {
        CheckoutSaga saga = sagas.findById(sagaId)
                .orElseThrow(() -> ApiException.notFound("SAGA_NOT_FOUND", "Saga not found"));
        List<Map<String, Object>> transitions = jdbc.queryForList(
                "SELECT from_state, to_state, trigger, detail, created_at FROM saga_transition WHERE saga_id = ? "
                        + "ORDER BY id", sagaId);
        return SagaView.from(saga, transitions);
    }

    @GetMapping("/attention")
    @Transactional(readOnly = true)
    public List<SagaView> needingAttention() {
        return sagas.findByNeedsAttentionTrueOrderByUpdatedAtDesc().stream()
                .map(s -> SagaView.from(s, List.of())).toList();
    }

    /** Ép watcher xử lý Saga ngay ở lần quét tới (ví dụ sau khi khắc phục sự cố downstream). */
    @PostMapping("/{sagaId}/retry-now")
    @Transactional
    public SagaView retryNow(@PathVariable UUID sagaId) {
        CheckoutSaga saga = sagas.lockById(sagaId)
                .orElseThrow(() -> ApiException.notFound("SAGA_NOT_FOUND", "Saga not found"));
        saga.expediteDeadline(clock.instant());
        return SagaView.from(saga, List.of());
    }

    public record SagaView(UUID sagaId, String state, String inventoryStep, String paymentStep, int stepAttempts,
                           Instant deadlineAt, boolean needsAttention, String failureReason, String lastError,
                           Instant updatedAt, List<Map<String, Object>> transitions) {
        static SagaView from(CheckoutSaga s, List<Map<String, Object>> transitions) {
            return new SagaView(s.getId(), s.getState().name(), s.getInventory().name(), s.getPayment().name(),
                    s.getStepAttempts(), s.getDeadlineAt(), s.isNeedsAttention(), s.getFailureReason(),
                    s.getLastError(), s.getUpdatedAt(), transitions);
        }
    }
}
