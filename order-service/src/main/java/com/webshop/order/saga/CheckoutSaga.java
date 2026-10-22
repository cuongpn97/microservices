package com.webshop.order.saga;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.webshop.order.saga.SagaCommand.CAPTURE_CREDIT;
import static com.webshop.order.saga.SagaCommand.COMMIT_INVENTORY;
import static com.webshop.order.saga.SagaCommand.RELEASE_CREDIT;
import static com.webshop.order.saga.SagaCommand.RELEASE_INVENTORY;
import static com.webshop.order.saga.SagaCommand.RESERVE_CREDIT;
import static com.webshop.order.saga.SagaCommand.RESERVE_INVENTORY;

/**
 * Máy trạng thái Saga checkout (orchestration), lưu bền trong bảng checkout_saga.
 * <p>
 * Toàn bộ logic chuyển trạng thái nằm ở đây, KHÔNG phụ thuộc DB/broker → unit test được thuần túy.
 * Mỗi handler trả về {@link Transition} gồm các command cần phát; orchestrator ghi chúng vào Outbox trong cùng
 * transaction với việc lưu state mới.
 * <p>
 * Quy tắc chống reply trễ/trùng: một reply chỉ được áp dụng khi state VÀ trạng thái participant đúng như đang chờ;
 * ngược lại bị bỏ qua (ignored) — an toàn vì mọi command phía participant đều idempotent và có tombstone.
 */
@Entity
@Table(name = "checkout_saga")
public class CheckoutSaga {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "total_vnd", nullable = false)
    private long totalVnd;

    @Column(name = "correlation_id", nullable = false)
    private String correlationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SagaState state;

    @Enumerated(EnumType.STRING)
    @Column(name = "inventory_step", nullable = false)
    private StepStatus inventory;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_step", nullable = false)
    private StepStatus payment;

    @Column(name = "step_attempts", nullable = false)
    private int stepAttempts;

    @Column(name = "deadline_at")
    private Instant deadlineAt;

    @Column(name = "needs_attention", nullable = false)
    private boolean needsAttention;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected CheckoutSaga() {
    }

    /** Bắt đầu Saga: state PENDING + command ReserveInventory. */
    public static Started start(UUID orderId, UUID userId, long totalVnd, String correlationId, Instant now,
                                SagaProperties props) {
        CheckoutSaga saga = new CheckoutSaga();
        saga.id = orderId;
        saga.userId = userId;
        saga.totalVnd = totalVnd;
        saga.correlationId = correlationId;
        saga.state = SagaState.PENDING;
        saga.inventory = StepStatus.RESERVE_PENDING;
        saga.payment = StepStatus.NOT_STARTED;
        saga.createdAt = now;
        saga.resetStep(now, props);
        return new Started(saga, List.of(RESERVE_INVENTORY));
    }

    // ------------------------------------------------------------------ replies từ Inventory

    public Transition onInventoryReserved(Instant now, SagaProperties props) {
        if (state != SagaState.PENDING || inventory != StepStatus.RESERVE_PENDING) {
            return Transition.ignored("InventoryReserved in " + state + "/" + inventory);
        }
        inventory = StepStatus.RESERVED;
        payment = StepStatus.RESERVE_PENDING;
        return moveTo(SagaState.INVENTORY_RESERVED, now, props, "inventory reserved", RESERVE_CREDIT);
    }

    public Transition onInventoryRejected(String reason, Instant now) {
        if (state != SagaState.PENDING || inventory != StepStatus.RESERVE_PENDING) {
            // Ví dụ: reply "RESERVATION_RELEASED" cho một ReserveInventory gửi lại, đến khi đang COMPENSATING —
            // không có nghĩa là release đã xong; chờ reply InventoryReleased.
            return Transition.ignored("InventoryReservationRejected in " + state + "/" + inventory);
        }
        inventory = StepStatus.REJECTED;
        failureReason = "INVENTORY_REJECTED:" + reason;
        // Không có gì đang bị giữ (payment chưa bắt đầu) → thất bại ngay, không cần bồi hoàn.
        return finish(SagaState.FAILED, now, failureReason);
    }

    public Transition onInventoryCommitted(Instant now) {
        if (state != SagaState.CONFIRMING || inventory != StepStatus.CONFIRM_PENDING) {
            return Transition.ignored("InventoryCommitted in " + state + "/" + inventory);
        }
        inventory = StepStatus.CONFIRMED;
        return completeIfDone(now, "inventory committed");
    }

    public Transition onInventoryReleased(Instant now) {
        if (state != SagaState.COMPENSATING || inventory != StepStatus.RELEASE_PENDING) {
            return Transition.ignored("InventoryReleased in " + state + "/" + inventory);
        }
        inventory = StepStatus.RELEASED;
        return failIfCompensated(now, "inventory released");
    }

    // ------------------------------------------------------------------ replies từ Payment

    public Transition onCreditReserved(Instant now, SagaProperties props) {
        if (state != SagaState.INVENTORY_RESERVED || payment != StepStatus.RESERVE_PENDING) {
            return Transition.ignored("CreditReserved in " + state + "/" + payment);
        }
        // Điểm không quay đầu: đã giữ cả hàng và credit → xác nhận cả hai (song song, đều idempotent).
        inventory = StepStatus.CONFIRM_PENDING;
        payment = StepStatus.CONFIRM_PENDING;
        return moveTo(SagaState.CONFIRMING, now, props, "credit reserved", COMMIT_INVENTORY, CAPTURE_CREDIT);
    }

    public Transition onCreditRejected(String reason, Instant now, SagaProperties props) {
        if (state != SagaState.INVENTORY_RESERVED || payment != StepStatus.RESERVE_PENDING) {
            return Transition.ignored("CreditReservationRejected in " + state + "/" + payment);
        }
        payment = StepStatus.REJECTED;
        failureReason = "CREDIT_REJECTED:" + reason;
        inventory = StepStatus.RELEASE_PENDING;
        return moveTo(SagaState.COMPENSATING, now, props, failureReason, RELEASE_INVENTORY);
    }

    public Transition onCreditCaptured(Instant now) {
        if (state != SagaState.CONFIRMING || payment != StepStatus.CONFIRM_PENDING) {
            return Transition.ignored("CreditCaptured in " + state + "/" + payment);
        }
        payment = StepStatus.CONFIRMED;
        return completeIfDone(now, "credit captured");
    }

    public Transition onCreditReleased(Instant now) {
        if (state != SagaState.COMPENSATING || payment != StepStatus.RELEASE_PENDING) {
            return Transition.ignored("CreditReleased in " + state + "/" + payment);
        }
        payment = StepStatus.RELEASED;
        return failIfCompensated(now, "credit released");
    }

    /**
     * Participant từ chối commit/capture/release — KHÔNG được xảy ra nếu bất biến được giữ
     * (reservation không hết hạn, release không bao giờ gửi sau capture). Không đoán mò: giữ nguyên state,
     * bật cờ needs_attention để người vận hành đối soát (xem docs/failure-scenarios.md).
     */
    public Transition onUnexpectedRejection(String replyType, String reason, Instant now) {
        if (state.isTerminal()) {
            return Transition.ignored(replyType + " in terminal " + state);
        }
        needsAttention = true;
        lastError = truncate(replyType + ":" + reason);
        updatedAt = now;
        return new Transition(state, state, List.of(), "needs attention: " + lastError, false);
    }

    // ------------------------------------------------------------------ timeout

    /**
     * Quá hạn chờ reply. Không giả định command đã thất bại — có thể participant ĐÃ làm nhưng reply bị chậm/mất.
     * Vì vậy luôn gửi lại command với cùng khóa idempotency (orderId), và khi bỏ cuộc thì release MỌI thứ có thể đã giữ.
     */
    public Transition onTimeout(Instant now, SagaProperties props) {
        stepAttempts++;
        return switch (state) {
            case PENDING -> {
                if (stepAttempts < props.maxReserveAttempts()) {
                    yield retryStep(now, props, "inventory reply timeout", RESERVE_INVENTORY);
                }
                failureReason = "INVENTORY_TIMEOUT";
                inventory = StepStatus.RELEASE_PENDING;
                yield moveTo(SagaState.COMPENSATING, now, props, failureReason, RELEASE_INVENTORY);
            }
            case INVENTORY_RESERVED -> {
                if (stepAttempts < props.maxReserveAttempts()) {
                    yield retryStep(now, props, "payment reply timeout", RESERVE_CREDIT);
                }
                failureReason = "PAYMENT_TIMEOUT";
                payment = StepStatus.RELEASE_PENDING;
                inventory = StepStatus.RELEASE_PENDING;
                // Thứ tự ngược phụ thuộc: credit (bước sau) trước, hàng (bước trước) sau.
                yield moveTo(SagaState.COMPENSATING, now, props, failureReason, RELEASE_CREDIT, RELEASE_INVENTORY);
            }
            case CONFIRMING -> {
                List<SagaCommand> resend = new ArrayList<>();
                if (inventory == StepStatus.CONFIRM_PENDING) {
                    resend.add(COMMIT_INVENTORY);
                }
                if (payment == StepStatus.CONFIRM_PENDING) {
                    resend.add(CAPTURE_CREDIT);
                }
                flagIfStuck(props);
                yield retryStep(now, props, "confirm reply timeout", resend.toArray(SagaCommand[]::new));
            }
            case COMPENSATING -> {
                List<SagaCommand> resend = new ArrayList<>();
                if (payment == StepStatus.RELEASE_PENDING) {
                    resend.add(RELEASE_CREDIT);
                }
                if (inventory == StepStatus.RELEASE_PENDING) {
                    resend.add(RELEASE_INVENTORY);
                }
                flagIfStuck(props);
                yield retryStep(now, props, "release reply timeout", resend.toArray(SagaCommand[]::new));
            }
            case COMPLETED, FAILED -> Transition.ignored("timeout in terminal state " + state);
        };
    }

    /** Operator yêu cầu xử lý lại ngay (ví dụ sau khi sửa sự cố downstream). */
    public void expediteDeadline(Instant now) {
        if (!state.isTerminal()) {
            deadlineAt = now;
            updatedAt = now;
        }
    }

    // ------------------------------------------------------------------ helpers

    private Transition completeIfDone(Instant now, String detail) {
        if (inventory == StepStatus.CONFIRMED && payment == StepStatus.CONFIRMED) {
            return finish(SagaState.COMPLETED, now, detail);
        }
        updatedAt = now;
        return new Transition(state, state, List.of(), detail, false);
    }

    private Transition failIfCompensated(Instant now, String detail) {
        if (inventory != StepStatus.RELEASE_PENDING && payment != StepStatus.RELEASE_PENDING) {
            return finish(SagaState.FAILED, now, detail);
        }
        updatedAt = now;
        return new Transition(state, state, List.of(), detail, false);
    }

    private Transition finish(SagaState terminal, Instant now, String detail) {
        SagaState from = state;
        state = terminal;
        deadlineAt = null;
        stepAttempts = 0;
        updatedAt = now;
        return new Transition(from, terminal, List.of(), detail, false);
    }

    private Transition moveTo(SagaState next, Instant now, SagaProperties props, String detail,
                              SagaCommand... commands) {
        SagaState from = state;
        state = next;
        resetStep(now, props);
        return new Transition(from, next, List.of(commands), detail, false);
    }

    private Transition retryStep(Instant now, SagaProperties props, String detail, SagaCommand... commands) {
        deadlineAt = now.plus(props.timeoutForAttempt(stepAttempts));
        updatedAt = now;
        return new Transition(state, state, List.of(commands), detail + " (attempt " + (stepAttempts + 1) + ")",
                false);
    }

    private void resetStep(Instant now, SagaProperties props) {
        stepAttempts = 0;
        deadlineAt = now.plus(props.timeoutForAttempt(0));
        updatedAt = now;
    }

    private void flagIfStuck(SagaProperties props) {
        if (stepAttempts >= props.attentionThreshold()) {
            needsAttention = true;
        }
    }

    private static String truncate(String s) {
        return s.length() > 500 ? s.substring(0, 500) : s;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public long getTotalVnd() {
        return totalVnd;
    }

    public String getCorrelationId() {
        return correlationId;
    }

    public SagaState getState() {
        return state;
    }

    public StepStatus getInventory() {
        return inventory;
    }

    public StepStatus getPayment() {
        return payment;
    }

    public int getStepAttempts() {
        return stepAttempts;
    }

    public Instant getDeadlineAt() {
        return deadlineAt;
    }

    public boolean isNeedsAttention() {
        return needsAttention;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public record Started(CheckoutSaga saga, List<SagaCommand> commands) {
    }

    /**
     * @param ignored true nếu reply bị bỏ qua (trùng/trễ/không khớp state) — không có thay đổi nào.
     */
    public record Transition(SagaState from, SagaState to, List<SagaCommand> commands, String detail,
                             boolean ignored) {
        static Transition ignored(String detail) {
            return new Transition(null, null, List.of(), detail, true);
        }

        public boolean stateChanged() {
            return !ignored && from != to;
        }
    }
}
