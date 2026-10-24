package com.webshop.payment.controller;

import com.webshop.common.security.WebshopJwt;
import com.webshop.common.web.ApiException;
import com.webshop.payment.dto.response.LedgerEntryResponse;
import com.webshop.payment.dto.response.WalletResponse;
import com.webshop.payment.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Ví của chính user: userId lấy từ JWT, không nhận từ client. */
@RestController
@RequestMapping("/api/wallet/me")
@RequiredArgsConstructor
public class WalletController {

    private final WalletService walletService;

    /** 404 WALLET_NOT_INITIALIZED: trạng thái tạm thời ngay sau đăng ký, trước khi UserRegistered được xử lý. */
    @GetMapping
    public WalletResponse me(@AuthenticationPrincipal Jwt jwt) {
        return walletService.findWallet(WebshopJwt.userId(jwt)).orElseThrow(() -> ApiException.notFound(
                "WALLET_NOT_INITIALIZED", "Wallet is being initialized, please retry shortly"));
    }

    @GetMapping("/ledger")
    public List<LedgerEntryResponse> myLedger(@AuthenticationPrincipal Jwt jwt,
                                              @RequestParam(defaultValue = "50") int limit) {
        return walletService.getLedger(WebshopJwt.userId(jwt), limit);
    }
}
