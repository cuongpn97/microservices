package com.webshop.payment.web;

import com.webshop.common.security.WebshopJwt;
import com.webshop.common.web.ApiException;
import com.webshop.payment.wallet.WalletService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
public class WalletController {

    private final WalletService wallets;

    public WalletController(WalletService wallets) {
        this.wallets = wallets;
    }

    /**
     * Ví của chính user (userId lấy từ JWT, không nhận từ client).
     * 404 WALLET_NOT_INITIALIZED: trạng thái tạm thời ngay sau đăng ký, trước khi UserRegistered được xử lý.
     */
    @GetMapping("/api/wallet/me")
    public WalletService.WalletView me(@AuthenticationPrincipal Jwt jwt) {
        return wallets.findWallet(WebshopJwt.userId(jwt)).orElseThrow(() -> ApiException.notFound(
                "WALLET_NOT_INITIALIZED", "Wallet is being initialized, please retry shortly"));
    }

    @GetMapping("/api/wallet/me/ledger")
    public List<WalletService.LedgerEntry> myLedger(@AuthenticationPrincipal Jwt jwt,
                                                    @RequestParam(defaultValue = "50") int limit) {
        return wallets.ledger(WebshopJwt.userId(jwt), Math.min(Math.max(limit, 1), 200));
    }

    @GetMapping("/admin/wallets/{userId}")
    public WalletService.WalletView wallet(@PathVariable UUID userId) {
        return wallets.findWallet(userId).orElseThrow(() -> ApiException.notFound("WALLET_NOT_FOUND", "Not found"));
    }

    @GetMapping("/admin/credit-reservations/{reservationId}")
    public Map<String, Object> reservation(@PathVariable UUID reservationId) {
        return wallets.findReservation(reservationId)
                .orElseThrow(() -> ApiException.notFound("RESERVATION_NOT_FOUND", "Not found"));
    }
}
