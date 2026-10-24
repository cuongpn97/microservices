package com.webshop.payment.controller;

import com.webshop.common.web.ApiException;
import com.webshop.payment.dto.response.CreditReservationResponse;
import com.webshop.payment.dto.response.WalletResponse;
import com.webshop.payment.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Endpoint vận hành: tra cứu ví và credit reservation phục vụ đối soát (xem docs/failure-scenarios.md).
 * ADMIN (chặn ở SecurityConfig: /admin/**) và không được route qua API Gateway.
 */
@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
public class WalletAdminController {

    private final WalletService walletService;

    @GetMapping("/wallets/{userId}")
    public WalletResponse wallet(@PathVariable UUID userId) {
        return walletService.findWallet(userId)
                .orElseThrow(() -> ApiException.notFound("WALLET_NOT_FOUND", "Not found"));
    }

    @GetMapping("/credit-reservations/{reservationId}")
    public CreditReservationResponse reservation(@PathVariable UUID reservationId) {
        return walletService.getReservation(reservationId);
    }
}
