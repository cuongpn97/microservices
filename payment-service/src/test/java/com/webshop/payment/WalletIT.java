package com.webshop.payment;

import com.webshop.payment.wallet.CreditOutcome;
import com.webshop.payment.wallet.WalletService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletIT extends AbstractPaymentIT {

    @Autowired
    WalletService wallets;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void initialCreditIsGrantedExactlyOnceEvenUnderConcurrentDuplicates() throws Exception {
        UUID userId = UUID.randomUUID();
        List<Boolean> results = concurrently(10, i -> wallets.createWalletWithInitialCredit(userId));

        assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        WalletService.WalletView wallet = wallets.findWallet(userId).orElseThrow();
        assertThat(wallet.balanceVnd()).isEqualTo(10_000_000L);
        assertThat(wallet.availableVnd()).isEqualTo(10_000_000L);
        assertThat(ledgerCount(userId, "INITIAL_CREDIT")).isEqualTo(1);
    }

    @Test
    void concurrentReservationsCannotOverspendAvailableBalance() throws Exception {
        UUID userId = newWallet();
        // 10.000.000 VND, 25 đơn × 1.000.000 → chỉ 10 đơn được giữ credit.
        List<CreditOutcome> outcomes = concurrently(25, i ->
                wallets.reserve(UUID.randomUUID(), userId, 1_000_000L));

        assertThat(outcomes.stream().filter(o -> o.replyType().equals(CreditOutcome.RESERVED)).count()).isEqualTo(10);
        assertThat(outcomes.stream().filter(o -> "INSUFFICIENT_FUNDS".equals(o.reason())).count()).isEqualTo(15);
        WalletService.WalletView wallet = wallets.findWallet(userId).orElseThrow();
        assertThat(wallet.reservedVnd()).isEqualTo(10_000_000L);
        assertThat(wallet.availableVnd()).isZero();
        assertThat(wallet.balanceVnd()).as("reserve không trừ balance, chỉ giữ").isEqualTo(10_000_000L);
    }

    @Test
    void reserveResentAfterTimeoutDoesNotReserveTwice() {
        UUID userId = newWallet();
        UUID orderId = UUID.randomUUID();

        // Lần 1 thành công nhưng reply "mất" (timeout phía Saga) → Saga gửi lại cùng orderId.
        CreditOutcome first = tx.execute(s -> wallets.reserve(orderId, userId, 2_000_000L));
        CreditOutcome retry = tx.execute(s -> wallets.reserve(orderId, userId, 2_000_000L));

        assertThat(first.applied()).isTrue();
        assertThat(retry.applied()).isFalse();
        assertThat(retry.replyType()).isEqualTo(CreditOutcome.RESERVED);
        assertThat(wallets.findWallet(userId).orElseThrow().reservedVnd()).isEqualTo(2_000_000L);
        assertThat(ledgerCount(userId, "RESERVE")).isEqualTo(1);
    }

    @Test
    void captureSucceedsButReplyIsLost_resendIsSafeAndChargesOnce() {
        UUID userId = newWallet();
        UUID orderId = UUID.randomUUID();
        tx.execute(s -> wallets.reserve(orderId, userId, 3_000_000L));

        CreditOutcome capture = tx.execute(s -> wallets.capture(orderId));
        // Order Service chết trước khi ghi nhận reply → watcher gửi lại CaptureCredit.
        CreditOutcome resent = tx.execute(s -> wallets.capture(orderId));

        assertThat(capture.applied()).isTrue();
        assertThat(resent.applied()).isFalse();
        assertThat(resent.replyType()).isEqualTo(CreditOutcome.CAPTURED);
        WalletService.WalletView wallet = wallets.findWallet(userId).orElseThrow();
        assertThat(wallet.balanceVnd()).isEqualTo(7_000_000L);
        assertThat(wallet.reservedVnd()).isZero();
        assertThat(wallet.totalCapturedVnd()).isEqualTo(3_000_000L);
        assertThat(ledgerCount(userId, "CAPTURE")).isEqualTo(1);
    }

    @Test
    void releaseIsIdempotentAndCapturedCreditCannotBeReleased() {
        UUID userId = newWallet();
        UUID released = UUID.randomUUID();
        UUID captured = UUID.randomUUID();
        tx.execute(s -> wallets.reserve(released, userId, 1_000_000L));
        tx.execute(s -> wallets.reserve(captured, userId, 1_000_000L));
        tx.execute(s -> wallets.capture(captured));

        CreditOutcome r1 = tx.execute(s -> wallets.release(released, userId, "INVENTORY_FAILED"));
        CreditOutcome r2 = tx.execute(s -> wallets.release(released, userId, "INVENTORY_FAILED"));
        CreditOutcome r3 = tx.execute(s -> wallets.release(captured, userId, "LATE"));

        assertThat(r1.applied()).isTrue();
        assertThat(r2.applied()).isFalse();
        assertThat(r3.replyType()).isEqualTo(CreditOutcome.RELEASE_REJECTED);
        WalletService.WalletView wallet = wallets.findWallet(userId).orElseThrow();
        assertThat(wallet.balanceVnd()).isEqualTo(9_000_000L);
        assertThat(wallet.reservedVnd()).isZero();
        assertThat(ledgerCount(userId, "RELEASE")).isEqualTo(1);
    }

    @Test
    void captureOfReleasedReservationIsRejected() {
        UUID userId = newWallet();
        UUID orderId = UUID.randomUUID();
        tx.execute(s -> wallets.reserve(orderId, userId, 1_000_000L));
        tx.execute(s -> wallets.release(orderId, userId, "TIMEOUT"));

        CreditOutcome capture = tx.execute(s -> wallets.capture(orderId));
        assertThat(capture.replyType()).isEqualTo(CreditOutcome.CAPTURE_REJECTED);
        assertThat(wallets.findWallet(userId).orElseThrow().balanceVnd()).isEqualTo(10_000_000L);
    }

    @Test
    void releaseBeforeReserveCreatesTombstoneSoLateReserveIsRejected() {
        UUID userId = newWallet();
        UUID orderId = UUID.randomUUID();
        tx.execute(s -> wallets.release(orderId, userId, "SAGA_TIMEOUT"));
        CreditOutcome late = tx.execute(s -> wallets.reserve(orderId, userId, 1_000_000L));

        assertThat(late.replyType()).isEqualTo(CreditOutcome.RESERVATION_REJECTED);
        assertThat(wallets.findWallet(userId).orElseThrow().reservedVnd()).isZero();
    }

    @Test
    void reserveForUserWithoutWalletIsRejectedNotFailed() {
        CreditOutcome outcome = tx.execute(s -> wallets.reserve(UUID.randomUUID(), UUID.randomUUID(), 1L));
        assertThat(outcome.replyType()).isEqualTo(CreditOutcome.RESERVATION_REJECTED);
        assertThat(outcome.reason()).isEqualTo("WALLET_NOT_FOUND");
    }

    @Test
    void ledgerIsAppendOnlyAndAvailableBalanceCannotGoNegative() {
        UUID userId = newWallet();
        assertThatThrownBy(() -> jdbc.update("UPDATE wallet_ledger SET amount_vnd = 1 WHERE wallet_id = "
                + "(SELECT id FROM wallet WHERE user_id = ?)", userId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM wallet_ledger WHERE wallet_id = "
                + "(SELECT id FROM wallet WHERE user_id = ?)", userId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE wallet SET reserved_vnd = balance_vnd + 1 WHERE user_id = ?", userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID newWallet() {
        UUID userId = UUID.randomUUID();
        tx.execute(s -> wallets.createWalletWithInitialCredit(userId));
        return userId;
    }

    private int ledgerCount(UUID userId, String type) {
        Integer n = jdbc.queryForObject("""
                SELECT count(*) FROM wallet_ledger l JOIN wallet w ON w.id = l.wallet_id
                WHERE w.user_id = ? AND l.entry_type = ?
                """, Integer.class, userId, type);
        return n == null ? 0 : n;
    }

    private interface Op<T> {
        T run(int index);
    }

    private <T> List<T> concurrently(int threads, Op<T> op) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int index = i;
                Callable<T> task = () -> {
                    start.await();
                    return tx.execute(s -> op.run(index));
                };
                futures.add(pool.submit(task));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
