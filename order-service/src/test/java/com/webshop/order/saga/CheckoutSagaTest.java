package com.webshop.order.saga;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.webshop.order.saga.SagaCommand.CAPTURE_CREDIT;
import static com.webshop.order.saga.SagaCommand.COMMIT_INVENTORY;
import static com.webshop.order.saga.SagaCommand.RELEASE_CREDIT;
import static com.webshop.order.saga.SagaCommand.RELEASE_INVENTORY;
import static com.webshop.order.saga.SagaCommand.RESERVE_CREDIT;
import static com.webshop.order.saga.SagaCommand.RESERVE_INVENTORY;
import static org.assertj.core.api.Assertions.assertThat;

/** Unit test thuần cho máy trạng thái Saga — không DB, không broker. */
class CheckoutSagaTest {

    private final SagaProperties props = new SagaProperties(Duration.ofSeconds(10), Duration.ofSeconds(60), 3, 4, 1000L);
    private Instant now = Instant.parse("2026-01-01T00:00:00Z");

    private CheckoutSaga newSaga() {
        CheckoutSaga.Started started = CheckoutSaga.start(UUID.randomUUID(), UUID.randomUUID(), 1_000L, "corr", now,
                props);
        assertThat(started.commands()).containsExactly(RESERVE_INVENTORY);
        assertThat(started.saga().getState()).isEqualTo(SagaState.PENDING);
        assertThat(started.saga().getDeadlineAt()).isEqualTo(now.plusSeconds(10));
        return started.saga();
    }

    @Test
    void happyPathReservesBothThenConfirmsBoth() {
        CheckoutSaga saga = newSaga();

        assertThat(saga.onInventoryReserved(now, props).commands()).containsExactly(RESERVE_CREDIT);
        assertThat(saga.getState()).isEqualTo(SagaState.INVENTORY_RESERVED);

        assertThat(saga.onCreditReserved(now, props).commands()).containsExactly(COMMIT_INVENTORY, CAPTURE_CREDIT);
        assertThat(saga.getState()).isEqualTo(SagaState.CONFIRMING);

        CheckoutSaga.Transition afterCommit = saga.onInventoryCommitted(now);
        assertThat(afterCommit.stateChanged()).isFalse();
        assertThat(saga.getState()).as("chưa xong khi mới commit kho").isEqualTo(SagaState.CONFIRMING);

        CheckoutSaga.Transition done = saga.onCreditCaptured(now);
        assertThat(done.to()).isEqualTo(SagaState.COMPLETED);
        assertThat(saga.getDeadlineAt()).isNull();
    }

    @Test
    void confirmRepliesMayArriveInAnyOrder() {
        CheckoutSaga saga = newSaga();
        saga.onInventoryReserved(now, props);
        saga.onCreditReserved(now, props);
        saga.onCreditCaptured(now);
        assertThat(saga.onInventoryCommitted(now).to()).isEqualTo(SagaState.COMPLETED);
    }

    @Test
    void inventoryRejectionFailsImmediatelyWithoutCompensation() {
        CheckoutSaga saga = newSaga();
        CheckoutSaga.Transition t = saga.onInventoryRejected("INSUFFICIENT_STOCK:x", now);
        assertThat(t.to()).isEqualTo(SagaState.FAILED);
        assertThat(t.commands()).isEmpty();
        assertThat(saga.getFailureReason()).isEqualTo("INVENTORY_REJECTED:INSUFFICIENT_STOCK:x");
    }

    @Test
    void creditRejectionCompensatesInventoryThenFails() {
        CheckoutSaga saga = newSaga();
        saga.onInventoryReserved(now, props);

        CheckoutSaga.Transition t = saga.onCreditRejected("INSUFFICIENT_FUNDS", now, props);
        assertThat(t.to()).isEqualTo(SagaState.COMPENSATING);
        assertThat(t.commands()).containsExactly(RELEASE_INVENTORY);
        assertThat(saga.getPayment()).isEqualTo(StepStatus.REJECTED);

        assertThat(saga.onInventoryReleased(now).to()).isEqualTo(SagaState.FAILED);
        assertThat(saga.getFailureReason()).isEqualTo("CREDIT_REJECTED:INSUFFICIENT_FUNDS");
    }

    @Test
    void duplicateAndLateRepliesAreIgnored() {
        CheckoutSaga saga = newSaga();
        saga.onInventoryReserved(now, props);
        assertThat(saga.onInventoryReserved(now, props).ignored()).as("duplicate").isTrue();
        assertThat(saga.onCreditCaptured(now).ignored()).as("out of order").isTrue();

        saga.onCreditRejected("INSUFFICIENT_FUNDS", now, props);
        assertThat(saga.onCreditReserved(now, props).ignored()).as("late reply during compensation").isTrue();
        saga.onInventoryReleased(now);
        assertThat(saga.onInventoryReleased(now).ignored()).as("after terminal").isTrue();
        assertThat(saga.getState()).isEqualTo(SagaState.FAILED);
    }

    @Test
    void reserveTimeoutResendsSameCommandWithBackoffThenCompensates() {
        CheckoutSaga saga = newSaga();

        now = now.plusSeconds(11);
        CheckoutSaga.Transition first = saga.onTimeout(now, props);
        assertThat(first.commands()).containsExactly(RESERVE_INVENTORY);
        assertThat(saga.getDeadlineAt()).isEqualTo(now.plusSeconds(20)); // 10s * 2^1

        now = now.plusSeconds(21);
        assertThat(saga.onTimeout(now, props).commands()).containsExactly(RESERVE_INVENTORY);
        assertThat(saga.getDeadlineAt()).isEqualTo(now.plusSeconds(40));

        now = now.plusSeconds(41);
        CheckoutSaga.Transition giveUp = saga.onTimeout(now, props);
        // Không biết Inventory đã giữ hàng hay chưa → release (tombstone nếu reserve chưa tới).
        assertThat(giveUp.to()).isEqualTo(SagaState.COMPENSATING);
        assertThat(giveUp.commands()).containsExactly(RELEASE_INVENTORY);
        assertThat(saga.getFailureReason()).isEqualTo("INVENTORY_TIMEOUT");

        assertThat(saga.onInventoryReserved(now, props).ignored()).as("reply trễ bị bỏ qua").isTrue();
        assertThat(saga.onInventoryReleased(now).to()).isEqualTo(SagaState.FAILED);
    }

    @Test
    void paymentTimeoutReleasesBothInReverseOrderAndWaitsForBoth() {
        CheckoutSaga saga = newSaga();
        saga.onInventoryReserved(now, props);
        saga.onTimeout(now, props);
        saga.onTimeout(now, props);
        CheckoutSaga.Transition giveUp = saga.onTimeout(now, props);

        assertThat(giveUp.commands()).containsExactly(RELEASE_CREDIT, RELEASE_INVENTORY);
        assertThat(saga.onCreditReleased(now).stateChanged()).isFalse();
        assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATING);
        assertThat(saga.onInventoryReleased(now).to()).isEqualTo(SagaState.FAILED);
    }

    @Test
    void confirmingNeverCompensatesItOnlyResendsPendingConfirmations() {
        CheckoutSaga saga = newSaga();
        saga.onInventoryReserved(now, props);
        saga.onCreditReserved(now, props);
        saga.onInventoryCommitted(now);

        for (int i = 1; i <= 6; i++) {
            CheckoutSaga.Transition t = saga.onTimeout(now, props);
            assertThat(t.commands()).as("chỉ gửi lại capture còn thiếu").isEqualTo(List.of(CAPTURE_CREDIT));
            assertThat(saga.getState()).isEqualTo(SagaState.CONFIRMING);
        }
        assertThat(saga.isNeedsAttention()).as("báo động sau ngưỡng").isTrue();
        assertThat(saga.getDeadlineAt()).as("backoff có trần").isEqualTo(now.plusSeconds(60));

        assertThat(saga.onCreditCaptured(now).to()).isEqualTo(SagaState.COMPLETED);
    }

    @Test
    void compensationKeepsRetryingAndIsNeverMarkedDoneWithoutReplies() {
        CheckoutSaga saga = newSaga();
        saga.onInventoryReserved(now, props);
        saga.onCreditRejected("INSUFFICIENT_FUNDS", now, props);
        for (int i = 0; i < 10; i++) {
            assertThat(saga.onTimeout(now, props).commands()).containsExactly(RELEASE_INVENTORY);
        }
        assertThat(saga.getState()).isEqualTo(SagaState.COMPENSATING);
        assertThat(saga.isNeedsAttention()).isTrue();
    }

    @Test
    void unexpectedRejectionDuringConfirmationFlagsForManualAttention() {
        CheckoutSaga saga = newSaga();
        saga.onInventoryReserved(now, props);
        saga.onCreditReserved(now, props);
        CheckoutSaga.Transition t = saga.onUnexpectedRejection("CreditCaptureRejected", "INVALID_STATE:RELEASED", now);
        assertThat(t.commands()).isEmpty();
        assertThat(saga.getState()).isEqualTo(SagaState.CONFIRMING);
        assertThat(saga.isNeedsAttention()).isTrue();
    }
}
