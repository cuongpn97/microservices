package com.webshop.order.service.impl;

import com.webshop.common.web.CorrelationIds;
import com.webshop.messaging.EventEnvelope;
import com.webshop.messaging.RoutingKeys;
import com.webshop.messaging.outbox.OutboxEvent;
import com.webshop.messaging.outbox.OutboxWriter;
import com.webshop.order.config.SagaProperties;
import com.webshop.order.entity.CheckoutSaga;
import com.webshop.order.entity.Order;
import com.webshop.order.entity.SagaTransition;
import com.webshop.order.enums.SagaCommand;
import com.webshop.order.enums.SagaState;
import com.webshop.order.messaging.command.CaptureCreditCommand;
import com.webshop.order.messaging.command.CommitInventoryCommand;
import com.webshop.order.messaging.command.ReleaseCreditCommand;
import com.webshop.order.messaging.command.ReleaseInventoryCommand;
import com.webshop.order.messaging.command.ReserveCreditCommand;
import com.webshop.order.messaging.command.ReserveInventoryCommand;
import com.webshop.order.messaging.event.OrderConfirmedEvent;
import com.webshop.order.messaging.event.SagaReplyEvent;
import com.webshop.order.repository.CheckoutSagaRepository;
import com.webshop.order.repository.OrderRepository;
import com.webshop.order.repository.SagaTransitionRepository;
import com.webshop.order.service.CheckoutSagaOrchestrator;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Orchestrator: nhận reply/timeout → gọi máy trạng thái {@link CheckoutSaga} → ghi state mới, nhật ký chuyển trạng thái,
 * trạng thái đơn hàng và các command tiếp theo vào Outbox — TẤT CẢ trong MỘT transaction cục bộ.
 * Không có "transaction phân tán": mỗi service chỉ commit dữ liệu của chính nó, gắn kết bằng message.
 * <p>
 * Phân vai: CheckoutSaga quyết định "chuyển sang đâu, cần phát command gì" (logic thuần, unit test được);
 * class này lo phần có side effect (DB, Outbox, metric, log).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutSagaOrchestratorImpl implements CheckoutSagaOrchestrator {

    private final CheckoutSagaRepository checkoutSagaRepository;
    private final OrderRepository orderRepository;
    private final SagaTransitionRepository sagaTransitionRepository;
    private final OutboxWriter outboxWriter;
    private final SagaProperties sagaProperties;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CheckoutSaga start(Order order, String correlationId) {
        Instant now = clock.instant();
        CheckoutSaga.Started started = CheckoutSaga.start(order.getId(), order.getUserId(), order.getTotalVnd(),
                correlationId, now, sagaProperties);
        CheckoutSaga saga = checkoutSagaRepository.save(started.saga());
        checkoutSagaRepository.flush();
        sagaTransitionRepository.append(saga.getId(),
                new SagaTransition(null, SagaState.PENDING, "CheckoutRequested", "saga started", now));
        emit(saga, order, started.commands(), null);
        meterRegistry.counter("webshop.saga.started").increment();
        return saga;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onReply(EventEnvelope envelope, SagaReplyEvent reply) {
        UUID sagaId = reply.reservationId();
        CheckoutSaga saga = checkoutSagaRepository.lockById(sagaId).orElse(null);
        if (saga == null) {
            log.warn("Reply {} for unknown saga {} ignored", envelope.eventType(), sagaId);
            return;
        }
        MDC.put(CorrelationIds.MDC_SAGA_ID, sagaId.toString());
        Instant now = clock.instant();
        String reason = reply.reason();
        CheckoutSaga.Transition t = switch (envelope.eventType()) {
            case "InventoryReserved" -> saga.onInventoryReserved(now, sagaProperties);
            case "InventoryReservationRejected" -> saga.onInventoryRejected(reason, now);
            case "InventoryCommitted" -> saga.onInventoryCommitted(now);
            case "InventoryReleased" -> saga.onInventoryReleased(now);
            case "CreditReserved" -> saga.onCreditReserved(now, sagaProperties);
            case "CreditReservationRejected" -> saga.onCreditRejected(reason, now, sagaProperties);
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

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void onTimeout(CheckoutSaga saga) {
        MDC.put(CorrelationIds.MDC_SAGA_ID, saga.getId().toString());
        try {
            SagaState before = saga.getState();
            CheckoutSaga.Transition t = saga.onTimeout(clock.instant(), sagaProperties);
            meterRegistry.counter("webshop.saga.timeouts", "state", before.name()).increment();
            log.warn("Saga step timed out sagaId={} state={} attempts={} -> {}", saga.getId(), before,
                    saga.getStepAttempts(), t.detail());
            apply(saga, t, "Timeout", null);
        } finally {
            MDC.remove(CorrelationIds.MDC_SAGA_ID);
        }
    }

    /** Áp dụng kết quả của máy trạng thái: ghi nhật ký, cập nhật đơn hàng, phát command. */
    private void apply(CheckoutSaga saga, CheckoutSaga.Transition t, String trigger, String causationId) {
        if (t.ignored()) {
            meterRegistry.counter("webshop.saga.replies.ignored").increment();
            log.info("Saga {} ignored {}: {}", saga.getId(), trigger, t.detail());
            return;
        }
        Instant now = clock.instant();
        Order order = orderRepository.findById(saga.getId()).orElseThrow();
        if (t.stateChanged()) {
            sagaTransitionRepository.append(saga.getId(), new SagaTransition(t.from(), t.to(), trigger, t.detail(), now));
            log.info("Saga {} {} -> {} on {}", saga.getId(), t.from(), t.to(), trigger);
        } else if (!t.commands().isEmpty()) {
            // Không đổi state nhưng có gửi lại command (retry sau timeout) → vẫn ghi để thấy được lịch sử retry.
            sagaTransitionRepository.append(saga.getId(), new SagaTransition(t.from(), t.to(), trigger, t.detail(), now));
        }
        if (saga.isNeedsAttention()) {
            meterRegistry.counter("webshop.saga.needs-attention").increment();
            log.error("Saga {} needs manual attention: {}", saga.getId(), t.detail());
        }
        if (t.to() == SagaState.COMPLETED) {
            order.confirm(now);
            meterRegistry.counter("webshop.saga.completed").increment();
            outboxWriter.append(event(saga, "OrderConfirmed", RoutingKeys.ORDER_CONFIRMED, causationId,
                    new OrderConfirmedEvent(order.getId(), order.getUserId(), order.getTotalVnd(),
                            order.getItems().stream()
                                    .map(i -> new OrderConfirmedEvent.Item(i.getProductId(), i.getQuantity()))
                                    .toList())));
        } else if (t.to() == SagaState.FAILED) {
            order.fail(saga.getFailureReason(), now);
            String category = saga.getFailureReason() == null ? "UNKNOWN" : saga.getFailureReason().split(":")[0];
            meterRegistry.counter("webshop.saga.failed", "reason", category).increment();
            // Không publish "OrderFailed": chưa có consumer nào bind routing key đó. Publisher dùng mandatory=true và
            // coi NO_ROUTE là lỗi (để command không bao giờ biến mất khi queue đích chưa tồn tại) → event không có
            // subscriber sẽ kẹt trong outbox mãi. Khi có consumer (ví dụ notification-service), thêm event tại đây.
        }
        emit(saga, order, t.commands(), causationId);
    }

    /** Đổi mỗi SagaCommand (máy trạng thái trả về) thành message thật, ghi vào Outbox. */
    private void emit(CheckoutSaga saga, Order order, List<SagaCommand> commands, String causationId) {
        UUID id = saga.getId();
        // String.valueOf: failureReason có thể null; giữ nguyên định dạng "null" như payload v1 đang gửi.
        String releaseReason = String.valueOf(saga.getFailureReason());
        for (SagaCommand command : commands) {
            OutboxEvent event = switch (command) {
                case RESERVE_INVENTORY -> event(saga, "ReserveInventory", RoutingKeys.INVENTORY_RESERVE, causationId,
                        new ReserveInventoryCommand(id, order.getItems().stream()
                                .map(i -> new ReserveInventoryCommand.Item(i.getProductId(), i.getQuantity()))
                                .toList()));
                case COMMIT_INVENTORY -> event(saga, "CommitInventory", RoutingKeys.INVENTORY_COMMIT, causationId,
                        new CommitInventoryCommand(id));
                case RELEASE_INVENTORY -> event(saga, "ReleaseInventory", RoutingKeys.INVENTORY_RELEASE, causationId,
                        new ReleaseInventoryCommand(id, releaseReason));
                case RESERVE_CREDIT -> event(saga, "ReserveCredit", RoutingKeys.PAYMENT_RESERVE, causationId,
                        new ReserveCreditCommand(id, saga.getUserId(), saga.getTotalVnd()));
                case CAPTURE_CREDIT -> event(saga, "CaptureCredit", RoutingKeys.PAYMENT_CAPTURE, causationId,
                        new CaptureCreditCommand(id, saga.getUserId()));
                case RELEASE_CREDIT -> event(saga, "ReleaseCredit", RoutingKeys.PAYMENT_RELEASE, causationId,
                        new ReleaseCreditCommand(id, saga.getUserId(), releaseReason));
            };
            outboxWriter.append(event);
        }
    }

    private OutboxEvent event(CheckoutSaga saga, String type, String routingKey, String causationId, Object payload) {
        return OutboxEvent.of("Order", saga.getId().toString(), type, routingKey, payload)
                .withContext(saga.getCorrelationId(), causationId, saga.getId().toString());
    }
}
