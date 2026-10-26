package com.webshop.cart.controller;

import com.webshop.cart.dto.request.AddItemRequest;
import com.webshop.cart.dto.request.SetQuantityRequest;
import com.webshop.cart.dto.response.CartResponse;
import com.webshop.cart.service.CartService;
import com.webshop.common.security.WebshopJwt;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Mọi endpoint thao tác trên giỏ của user trong JWT ("sub"). Không có endpoint nào nhận userId từ client.
 * Các thao tác sửa trả giỏ KHÔNG kèm giá; muốn có giá thì GET lại.
 */
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    /**
     * @param pricing false: chỉ trả skuId/quantity (Order Service dùng — nó tự lấy giá từ Product Service).
     */
    @GetMapping
    public CartResponse get(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "true") boolean pricing) {
        return cartService.getCart(WebshopJwt.userId(jwt), pricing);
    }

    @PostMapping("/items")
    public CartResponse add(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AddItemRequest request) {
        return cartService.addItem(WebshopJwt.userId(jwt), request.skuId(), request.quantity());
    }

    @PutMapping("/items/{skuId}")
    public CartResponse set(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID skuId,
                            @Valid @RequestBody SetQuantityRequest request) {
        return cartService.setQuantity(WebshopJwt.userId(jwt), skuId, request.quantity());
    }

    @DeleteMapping("/items/{skuId}")
    public CartResponse remove(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID skuId) {
        return cartService.removeItem(WebshopJwt.userId(jwt), skuId);
    }

    @DeleteMapping
    public CartResponse clear(@AuthenticationPrincipal Jwt jwt) {
        return cartService.clear(WebshopJwt.userId(jwt));
    }
}
