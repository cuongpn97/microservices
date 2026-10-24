package com.webshop.order.controller;

import com.webshop.common.security.WebshopJwt;
import com.webshop.order.dto.CheckoutResult;
import com.webshop.order.dto.response.OrderResponse;
import com.webshop.order.service.CheckoutService;
import com.webshop.order.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "Checkout (khởi động Saga) và theo dõi trạng thái đơn hàng")
@RequiredArgsConstructor
public class OrderController {

    private final CheckoutService checkoutService;
    private final OrderService orderService;

    /**
     * Checkout giỏ hàng của user hiện tại. Trả 202 Accepted + orderId; client poll GET /api/orders/{id}.
     * Gửi lại với cùng Idempotency-Key → 200 + cùng đơn hàng.
     */
    @PostMapping("/checkout")
    @Operation(summary = "Checkout giỏ hàng của user hiện tại",
            description = "Tạo đơn PENDING + Saga. Poll GET /api/orders/{orderId} tới khi CONFIRMED hoặc FAILED. "
                    + "Gửi lại cùng Idempotency-Key trả lại đúng đơn cũ (200).")
    @ApiResponse(responseCode = "202", description = "Đơn mới đã được ghi nhận, Saga đang chạy")
    @ApiResponse(responseCode = "200", description = "Idempotent replay: đơn đã tồn tại với key này")
    @ApiResponse(responseCode = "409", description = "CHECKOUT_ALREADY_IN_PROGRESS: phiên bản giỏ này đã có đơn đang chạy")
    @ApiResponse(responseCode = "422", description = "CART_EMPTY / PRODUCT_UNAVAILABLE")
    @ApiResponse(responseCode = "503", description = "Cart/Product Service không sẵn sàng — retry với cùng key")
    public ResponseEntity<OrderResponse> checkout(@AuthenticationPrincipal Jwt jwt,
                                                  @Parameter(in = ParameterIn.HEADER, required = true,
                                                          description = "8-100 ký tự [A-Za-z0-9_:.-], ví dụ checkout-<uuid>")
                                                  @RequestHeader(name = "Idempotency-Key", required = false)
                                                  String idempotencyKey) {
        CheckoutResult result = checkoutService.checkout(WebshopJwt.userId(jwt), jwt.getTokenValue(), idempotencyKey);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .location(URI.create("/api/orders/" + result.order().orderId()))
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(result.order());
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Trạng thái đơn hàng (poll)", description = "status: PENDING/CONFIRMED/FAILED; "
            + "sagaState: PENDING, INVENTORY_RESERVED, CONFIRMING, COMPENSATING, COMPLETED, FAILED")
    public OrderResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        return orderService.getOrder(WebshopJwt.userId(jwt), orderId);
    }

    @GetMapping
    public List<OrderResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        return orderService.getMyOrders(WebshopJwt.userId(jwt));
    }
}
