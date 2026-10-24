package com.webshop.payment.service;

import com.webshop.payment.dto.CreditOutcome;
import com.webshop.payment.dto.response.CreditReservationResponse;
import com.webshop.payment.dto.response.LedgerEntryResponse;
import com.webshop.payment.dto.response.WalletResponse;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WalletService {

    // ---- Gọi bởi listener ----
    // Bắt buộc chạy trong transaction có sẵn của consumer (Propagation.MANDATORY), cùng transaction với Inbox/Outbox.
    // Idempotent theo khóa nghiệp vụ: gọi lại nhiều lần chỉ lần đầu có applied=true / trả true.

    /**
     * Tạo ví + tặng credit ban đầu (khi nhận UserRegistered). Gọi lại bao nhiêu lần cũng chỉ tặng MỘT lần.
     *
     * @return true nếu lần này thực sự tạo ví.
     */
    boolean createWalletWithInitialCredit(UUID userId);

    /** Giữ tiền cho đơn. Không đủ tiền / chưa có ví → CreditReservationRejected. */
    CreditOutcome reserve(UUID reservationId, UUID userId, long amountVnd);

    /** Trừ tiền đã giữ (đơn thành công). */
    CreditOutcome capture(UUID reservationId);

    /** Trả tiền đang giữ (bù trừ Saga). Đến trước reserve → tạo tombstone RELEASED. */
    CreditOutcome release(UUID reservationId, UUID userId, String reason);

    // ---- HTTP API ----

    /** Optional vì caller quyết định mã lỗi: /me trả WALLET_NOT_INITIALIZED, admin trả WALLET_NOT_FOUND. */
    Optional<WalletResponse> findWallet(UUID userId);

    /** Mới nhất trước; limit được kẹp trong khoảng 1..200. */
    List<LedgerEntryResponse> getLedger(UUID userId, int limit);

    CreditReservationResponse getReservation(UUID reservationId);
}
