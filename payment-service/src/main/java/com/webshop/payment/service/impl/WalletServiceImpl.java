package com.webshop.payment.service.impl;

import com.webshop.common.web.ApiException;
import com.webshop.common.web.CorrelationIds;
import com.webshop.payment.dto.CreditOutcome;
import com.webshop.payment.dto.response.CreditReservationResponse;
import com.webshop.payment.dto.response.LedgerEntryResponse;
import com.webshop.payment.dto.response.WalletResponse;
import com.webshop.payment.entity.CreditReservation;
import com.webshop.payment.entity.WalletBalance;
import com.webshop.payment.enums.CreditReservationStatus;
import com.webshop.payment.enums.LedgerEntryType;
import com.webshop.payment.mapper.WalletMapper;
import com.webshop.payment.repository.CreditReservationRepository;
import com.webshop.payment.repository.WalletLedgerRepository;
import com.webshop.payment.repository.WalletRepository;
import com.webshop.payment.service.WalletService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.webshop.payment.dto.CreditOutcome.CAPTURED;
import static com.webshop.payment.dto.CreditOutcome.CAPTURE_REJECTED;
import static com.webshop.payment.dto.CreditOutcome.RELEASED;
import static com.webshop.payment.dto.CreditOutcome.RELEASE_REJECTED;
import static com.webshop.payment.dto.CreditOutcome.RESERVATION_REJECTED;
import static com.webshop.payment.dto.CreditOutcome.RESERVED;

/**
 * Nghiệp vụ ví. Service chỉ chứa luồng quyết định; mọi câu SQL nằm ở repository. Các nguyên tắc an toàn tiền:
 * <ul>
 *     <li><b>Atomic conditional update</b>: kiểm tra số dư và ghi trong MỘT câu UPDATE (xem WalletRepository).</li>
 *     <li><b>Khóa idempotency nghiệp vụ</b>: credit_reservation.id = orderId; ledger.idempotency_key unique.</li>
 *     <li><b>Ledger append-only</b>: mỗi thay đổi số dư ghi một dòng; trigger DB cấm UPDATE/DELETE.</li>
 *     <li><b>CHECK constraint</b>: reserved_vnd &lt;= balance_vnd → available không bao giờ âm.</li>
 * </ul>
 */
@Slf4j
@Service
public class WalletServiceImpl implements WalletService {

    private final WalletRepository walletRepository;
    private final CreditReservationRepository creditReservationRepository;
    private final WalletLedgerRepository walletLedgerRepository;
    private final WalletMapper walletMapper;
    private final Clock clock;
    private final long initialCreditVnd;

    // Viết tay vì @RequiredArgsConstructor không chép @Value sang tham số constructor.
    public WalletServiceImpl(WalletRepository walletRepository,
                             CreditReservationRepository creditReservationRepository,
                             WalletLedgerRepository walletLedgerRepository, WalletMapper walletMapper, Clock clock,
                             @Value("${webshop.wallet.initial-credit-vnd:10000000}") long initialCreditVnd) {
        this.walletRepository = walletRepository;
        this.creditReservationRepository = creditReservationRepository;
        this.walletLedgerRepository = walletLedgerRepository;
        this.walletMapper = walletMapper;
        this.clock = clock;
        this.initialCreditVnd = initialCreditVnd;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean createWalletWithInitialCredit(UUID userId) {
        UUID walletId = UUID.randomUUID();
        // unique(user_id) trên wallet + unique(idempotency_key) trên ledger → chỉ tặng một lần.
        if (!walletRepository.insertIfAbsent(walletId, userId, initialCreditVnd, clock.instant())) {
            log.info("Wallet already exists, initial credit not granted again userId={}", userId);
            return false;
        }
        appendLedger(walletId, LedgerEntryType.INITIAL_CREDIT, initialCreditVnd,
                new WalletBalance(initialCreditVnd, 0), "initial-credit:" + userId, userId.toString());
        log.info("Wallet created with initial credit userId={}", userId);
        return true;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CreditOutcome reserve(UUID reservationId, UUID userId, long amountVnd) {
        Instant now = clock.instant();
        boolean claimed = creditReservationRepository.insertReservedIfAbsent(reservationId, userId,
                Math.max(amountVnd, 0), now);
        CreditReservation reservation = creditReservationRepository.lockById(reservationId).orElseThrow();
        if (!claimed) {
            return replayReserve(reservation, userId, amountVnd);
        }
        if (amountVnd <= 0) {
            return reject(reservationId, amountVnd, "INVALID_AMOUNT");
        }
        UUID walletId = reservation.walletId();
        if (walletId == null) {
            // Trạng thái tạm thời: user vừa đăng ký nhưng UserRegistered chưa được xử lý xong.
            return reject(reservationId, amountVnd, "WALLET_NOT_FOUND");
        }
        Optional<WalletBalance> balance = walletRepository.reserve(walletId, amountVnd, now);
        if (balance.isEmpty()) {
            return reject(reservationId, amountVnd, "INSUFFICIENT_FUNDS");
        }
        appendLedger(walletId, LedgerEntryType.RESERVE, amountVnd, balance.get(), "reserve:" + reservationId,
                reservationId.toString());
        log.info("Credit reserved reservationId={} amountVnd={}", reservationId, amountVnd);
        return new CreditOutcome(RESERVED, reservationId, CreditReservationStatus.RESERVED.name(), null, amountVnd,
                true);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CreditOutcome capture(UUID reservationId) {
        Optional<CreditReservation> found = creditReservationRepository.lockById(reservationId);
        if (found.isEmpty()) {
            return new CreditOutcome(CAPTURE_REJECTED, reservationId, null, "RESERVATION_NOT_FOUND", 0, false);
        }
        CreditReservation reservation = found.get();
        long amount = reservation.amountVnd();
        return switch (reservation.status()) {
            case RESERVED -> {
                Instant now = clock.instant();
                WalletBalance balance = walletRepository.capture(reservation.walletId(), amount, now)
                        .orElseThrow(() -> new IllegalStateException("Wallet invariant violated for " + reservationId));
                creditReservationRepository.updateStatus(reservationId, CreditReservationStatus.CAPTURED, null, now);
                appendLedger(reservation.walletId(), LedgerEntryType.CAPTURE, amount, balance,
                        "capture:" + reservationId, reservationId.toString());
                log.info("Credit captured reservationId={} amountVnd={}", reservationId, amount);
                yield new CreditOutcome(CAPTURED, reservationId, CreditReservationStatus.CAPTURED.name(), null, amount,
                        true);
            }
            // Capture lặp lại (Saga gửi lại sau timeout, hoặc reply trước đó bị mất) → trả lời giống hệt, không trừ lần 2.
            case CAPTURED -> new CreditOutcome(CAPTURED, reservationId, CreditReservationStatus.CAPTURED.name(), null,
                    amount, false);
            case REJECTED, RELEASED -> new CreditOutcome(CAPTURE_REJECTED, reservationId, reservation.status().name(),
                    "INVALID_STATE:" + reservation.status(), amount, false);
        };
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public CreditOutcome release(UUID reservationId, UUID userId, String reason) {
        Instant now = clock.instant();
        if (creditReservationRepository.insertReleasedTombstoneIfAbsent(reservationId, userId, now)) {
            log.info("Release arrived before reserve; tombstone created reservationId={}", reservationId);
            return new CreditOutcome(RELEASED, reservationId, CreditReservationStatus.RELEASED.name(),
                    "RELEASED_BEFORE_RESERVE", 0, true);
        }
        CreditReservation reservation = creditReservationRepository.lockById(reservationId).orElseThrow();
        long amount = reservation.amountVnd();
        return switch (reservation.status()) {
            case RESERVED -> {
                WalletBalance balance = walletRepository.releaseReserved(reservation.walletId(), amount, now)
                        .orElseThrow(() -> new IllegalStateException("Wallet invariant violated for " + reservationId));
                creditReservationRepository.updateStatus(reservationId, CreditReservationStatus.RELEASED, reason, now);
                appendLedger(reservation.walletId(), LedgerEntryType.RELEASE, amount, balance,
                        "release:" + reservationId, reservationId.toString());
                log.info("Credit released reservationId={} amountVnd={}", reservationId, amount);
                yield new CreditOutcome(RELEASED, reservationId, CreditReservationStatus.RELEASED.name(), reason,
                        amount, true);
            }
            case RELEASED, REJECTED -> new CreditOutcome(RELEASED, reservationId, reservation.status().name(),
                    reservation.reason(), amount, false);
            // Đã capture thì không "release" được — cần nghiệp vụ hoàn tiền (refund), ngoài phạm vi; xem failure-scenarios.
            case CAPTURED -> new CreditOutcome(RELEASE_REJECTED, reservationId, reservation.status().name(),
                    "ALREADY_CAPTURED", amount, false);
        };
    }

    @Override
    public Optional<WalletResponse> findWallet(UUID userId) {
        return walletRepository.findSummaryByUserId(userId).map(walletMapper::toWalletResponse);
    }

    @Override
    public List<LedgerEntryResponse> getLedger(UUID userId, int limit) {
        int clamped = Math.min(Math.max(limit, 1), 200);
        return walletMapper.toLedgerEntryResponses(walletLedgerRepository.findByUserId(userId, clamped));
    }

    @Override
    public CreditReservationResponse getReservation(UUID reservationId) {
        return creditReservationRepository.findById(reservationId).map(walletMapper::toCreditReservationResponse)
                .orElseThrow(() -> ApiException.notFound("RESERVATION_NOT_FOUND", "Not found"));
    }

    private CreditOutcome replayReserve(CreditReservation existing, UUID userId, long amountVnd) {
        UUID id = existing.id();
        CreditReservationStatus status = existing.status();
        long amount = existing.amountVnd();
        if (!userId.equals(existing.userId()) || (status == CreditReservationStatus.RESERVED && amount != amountVnd)) {
            // Cùng khóa idempotency nhưng nội dung khác → bất thường, không áp dụng; ghi log để điều tra.
            log.warn("Reserve replay with different parameters reservationId={}", id);
        }
        return switch (status) {
            case RESERVED, CAPTURED -> new CreditOutcome(RESERVED, id, status.name(), null, amount, false);
            case REJECTED -> new CreditOutcome(RESERVATION_REJECTED, id, status.name(), existing.reason(), amount,
                    false);
            case RELEASED -> new CreditOutcome(RESERVATION_REJECTED, id, status.name(), "RESERVATION_RELEASED", amount,
                    false);
        };
    }

    private CreditOutcome reject(UUID reservationId, long amountVnd, String reason) {
        creditReservationRepository.updateStatus(reservationId, CreditReservationStatus.REJECTED, reason,
                clock.instant());
        log.info("Credit reservation rejected reservationId={} reason={}", reservationId, reason);
        return new CreditOutcome(RESERVATION_REJECTED, reservationId, CreditReservationStatus.REJECTED.name(), reason,
                amountVnd, true);
    }

    private void appendLedger(UUID walletId, LedgerEntryType type, long amount, WalletBalance after,
                              String idempotencyKey, String referenceId) {
        walletLedgerRepository.append(walletId, type, amount, after.balanceVnd(), after.reservedVnd(), idempotencyKey,
                referenceId, CorrelationIds.current(), clock.instant());
    }
}
