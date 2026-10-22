package com.webshop.order.saga;

import com.webshop.common.web.CorrelationIds;
import com.webshop.messaging.EventEnvelope;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.outbox.OutboxEvent;
import com.webshop.messaging.outbox.OutboxWriter;
import com.webshop.order.domain.Order;
import com.webshop.order.domain.OrderRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestrator: nhận reply/timeout → gọi máy trạng thái {@link CheckoutSaga} → ghi state mới, nhật ký chuyển trạng thái,
 * trạng thái đơn hàng và các command tiếp theo vào Outbox — TẤT CẢ trong MỘT transaction cục bộ.
 * Không có "transaction phân tán": mỗi service chỉ commit dữ liệu của chính nó, gắn kết bằng message.
 */
@Service
public class CheckoutSagaOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(CheckoutSagaOrchestrator.class);

    private final CheckoutSagaRepository sagas;
    private final OrderRepository orders;
    private final OutboxWriter outbox;
    private final JdbcTemplate jdbc;
    private final SagaProperties props;
    private final Clock clock;
    private final MeterRegistry meters;

    public CheckoutSagaOrchestrator(CheckoutSagaRepository sagas, OrderRepository orders, OutboxWriter outbox,
                                    JdbcTemplate jdbc, SagaProperties props, Clock clock, MeterRegistry meters) {
        this.sagas = sagas;
        this.orders = orders;
        this.outbox = outbox;
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
        this.meters = meters;
    }

    /** Gọi trong transaction tạo đơn hàng (CheckoutService). */
    @Transactional(propagation = Propagation.MANDATORY)
    public CheckoutSaga start(Order order, String correlationId) {
        Instant now = clock.instant();
        CheckoutSaga.Started started = CheckoutSaga.start(order.getId(), order.getUserId(), order.getTotalVnd(),
                correlationId, now, props);
        CheckoutSaga saga = sagas.save(started.saga());
        sagas.flush();
        logTransition(saga.getId(), null, SagaState.PENDING, "CheckoutRequested", "saga started", now);
        emit(saga, order, started.commands(), null);
        meters.counter("webshop.saga.started").increment();
        return saga;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void onReply(EventEnvelope envelope, ReplyPayload reply) {
        UUID sagaId = reply.reservationId();
        CheckoutSaga saga = sagas.lockById(sagaId).orElse(null);
        if (saga == null) {
            log.warn("Reply {} for unknown saga {} ignored", envelope.eventType(), sagaId);
            return;
        }
        MDC.put(CorrelationIds.MDC_SAGA_ID, sagaId.toString());
        Instant now = clock.instant();
        String reason = reply.reason();
        CheckoutSaga.Transition t = switch (envelope.eventType()) {
            case "InventoryReserved" -> saga.onInventoryReserved(now, props);
            case "InventoryReservationRejected" -> saga.onInventoryRejected(reason, now);
            case "InventoryCommitted" -> saga.onInventoryCommitted(now);
            case "InventoryReleased" -> saga.onInventoryReleased(now);
            case "CreditReserved" -> saga.onCreditReserved(now, props);
            case "CreditReservationRejected" -> saga.onCreditRejected(reason, now, props);
            case "CreditCaptured" -> saga.onCreditCaptured(now);
            case "CreditReleased" -> saga.onCreditReleased(now);
            case "InventoryCommitRejected", "InventoryReleaseRejected", "CreditCaptureRejected",
                 "CreditReleaseRejected" -> saga.onUnexpectedRejection(envelope.eventType(), reason, now);
            default -> {
                log.warn("Unknown reply type {} for saga {}", envelope.eventType(), sagaId);
                yield null;
            }
        };
        if (t == null) {
            return;
        }
        apply(saga, t, envelope.eventType(), envelope.eventId().toString());
    }

    /** Gọi bởi {@link SagaTimeoutWatcher} cho Saga đã quá deadline (đã bị khóa bằng FOR UPDATE SKIP LOCKED). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void onTimeout(CheckoutSaga saga) {
        MDC.put(CorrelationIds.MDC_SAGA_ID, saga.getId().toString());
        try {
            SagaState before = saga.getState();
            CheckoutSaga.Transition t = saga.onTimeout(clock.instant(), props);
            meters.counter("webshop.saga.timeouts", "state", before.name()).increment();
            log.warn("Saga step timed out sagaId={} state={} attempts={} -> {}", saga.getId(), before,
                    saga.getStepAttempts(), t.detail());
            apply(saga, t, "Timeout", null);
        } finally {
            MDC.remove(CorrelationIds.MDC_SAGA_ID);
        }
    }

    private void apply(CheckoutSaga saga, CheckoutSaga.Transition t, String trigger, String causationId) {
        if (t.ignored()) {
            meters.counter("webshop.saga.replies.ignored").increment();
            log.info("Saga {} ignored {}: {}", saga.getId(), trigger, t.detail());
            return;
        }
        Instant now = clock.instant();
        Order order = orders.findById(saga.getId()).orElseThrow();
        if (t.stateChanged()) {
            logTransition(saga.getId(), t.from(), t.to(), trigger, t.detail(), now);
            log.info("Saga {} {} -> {} on {}", saga.getId(), t.from(), t.to(), trigger);
        } else if (!t.commands().isEmpty()) {
            logTransition(saga.getId(), t.from(), t.to(), trigger, t.detail(), now);
        }
        if (saga.isNeedsAttention()) {
            meters.counter("webshop.saga.needs-attention").increment();
            log.error("Saga {} needs manual attention: {}", saga.getId(), t.detail());
        }
        if (t.to() == SagaState.COMPLETED) {
            order.confirm(now);
            meters.counter("webshop.saga.completed").increment();
            outbox.append(event(saga, "OrderConfirmed", RoutingKeys.ORDER_CONFIRMED, causationId, Map.of(
                    "orderId", order.getId(),
                    "userId", order.getUserId(),
                    "totalVnd", order.getTotalVnd(),
                    "items", order.getItems().stream()
                            .map(i -> Map.of("productId", i.getProductId(), "quantity", i.getQuantity())).toList())));
        } else if (t.to() == SagaState.FAILED) {
            order.fail(saga.getFailureReason(), now);
            String category = saga.getFailureReason() == null ? "UNKNOWN" : saga.getFailureReason().split(":")[0];
            meters.counter("webshop.saga.failed", "reason", category).increment();
            // Không publish "OrderFailed": chưa có consumer nào bind routing key đó. Publisher dùng mandatory=true và
            // coi NO_ROUTE là lỗi (để command không bao giờ biến mất khi queue đích chưa tồn tại) → event không có
            // subscriber sẽ kẹt trong outbox mãi. Khi có consumer (ví dụ notification-service), thêm event tại đây.
        }
        emit(saga, order, t.commands(), causationId);
    }

    private void emit(CheckoutSaga saga, Order order, List<SagaCommand> commands, String causationId) {
        UUID id = saga.getId();
        for (SagaCommand command : commands) {
            OutboxEvent event = switch (command) {
                case RESERVE_INVENTORY -> event(saga, "ReserveInventory", RoutingKeys.INVENTORY_RESERVE, causationId,
                        Map.of("reservationId", id, "items", order.getItems().stream()
                                .map(i -> Map.of("productId", i.getProductId(), "quantity", i.getQuantity()))
                                .toList()));
                case COMMIT_INVENTORY -> event(saga, "CommitInventory", RoutingKeys.INVENTORY_COMMIT, causationId,
                        Map.of("reservationId", id));
                case RELEASE_INVENTORY -> event(saga, "ReleaseInventory", RoutingKeys.INVENTORY_RELEASE, causationId,
                        Map.of("reservationId", id, "reason", String.valueOf(saga.getFailureReason())));
                case RESERVE_CREDIT -> event(saga, "ReserveCredit", RoutingKeys.PAYMENT_RESERVE, causationId,
                        Map.of("reservationId", id, "userId", saga.getUserId(), "amountVnd", saga.getTotalVnd()));
                case CAPTURE_CREDIT -> event(saga, "CaptureCredit", RoutingKeys.PAYMENT_CAPTURE, causationId,
                        Map.of("reservationId", id, "userId", saga.getUserId()));
                case RELEASE_CREDIT -> event(saga, "ReleaseCredit", RoutingKeys.PAYMENT_RELEASE, causationId,
                        Map.of("reservationId", id, "userId", saga.getUserId(),
                                "reason", String.valueOf(saga.getFailureReason())));
            };
            outbox.append(event);
        }
    }

    private OutboxEvent event(CheckoutSaga saga, String type, String routingKey, String causationId, Object payload) {
        return OutboxEvent.of("Order", saga.getId().toString(), type, routingKey, payload)
                .withContext(saga.getCorrelationId(), causationId, saga.getId().toString());
    }

    private void logTransition(UUID sagaId, SagaState from, SagaState to, String trigger, String detail,
                               Instant now) {
        jdbc.update("INSERT INTO saga_transition (saga_id, from_state, to_state, trigger, detail, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)", sagaId, from == null ? null : from.name(), to.name(), trigger,
                detail == null ? null : detail.length() > 300 ? detail.substring(0, 300) : detail,
                Timestamp.from(now));
    }

    /** Phần payload chung của mọi reply từ Inventory/Payment. */
    public record ReplyPayload(UUID reservationId, String status, String reason) {
    }
}
