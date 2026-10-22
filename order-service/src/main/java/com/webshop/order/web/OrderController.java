package com.webshop.order.web;

import com.webshop.common.security.WebshopJwt;
import com.webshop.common.web.ApiException;
import com.webshop.order.checkout.CheckoutService;
import com.webshop.order.domain.Order;
import com.webshop.order.domain.OrderRepository;
import com.webshop.order.saga.CheckoutSaga;
import com.webshop.order.saga.CheckoutSagaRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/orders")
@Tag(name = "Orders", description = "Checkout (khởi động Saga) và theo dõi trạng thái đơn hàng")
public class OrderController {

    private final CheckoutService checkoutService;
    private final OrderRepository orders;
    private final CheckoutSagaRepository sagas;

    public OrderController(CheckoutService checkoutService, OrderRepository orders, CheckoutSagaRepository sagas) {
        this.checkoutService = checkoutService;
        this.orders = orders;
        this.sagas = sagas;
    }

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
        CheckoutService.CheckoutResult result = checkoutService.checkout(WebshopJwt.userId(jwt), jwt.getTokenValue(),
                idempotencyKey);
        OrderResponse body = toResponse(result.order());
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                .location(URI.create("/api/orders/" + result.order().getId()))
                .header("Idempotent-Replayed", String.valueOf(result.replayed()))
                .body(body);
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Trạng thái đơn hàng (poll)", description = "status: PENDING/CONFIRMED/FAILED; "
            + "sagaState: PENDING, INVENTORY_RESERVED, CONFIRMING, COMPENSATING, COMPLETED, FAILED")
    @Transactional(readOnly = true)
    public OrderResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        Order order = orders.findById(orderId)
                // Đơn của người khác → 404 (không tiết lộ sự tồn tại).
                .filter(o -> o.getUserId().equals(WebshopJwt.userId(jwt)))
                .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "Order not found"));
        return toResponse(order);
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<OrderResponse> mine(@AuthenticationPrincipal Jwt jwt) {
        List<Order> page = orders.findByUserIdOrderByCreatedAtDesc(WebshopJwt.userId(jwt), PageRequest.of(0, 50));
        // Một câu IN cho saga của cả trang thay vì findById trong vòng lặp (N+1).
        Map<UUID, CheckoutSaga> sagaById = sagas.findAllById(page.stream().map(Order::getId).toList()).stream()
                .collect(Collectors.toMap(CheckoutSaga::getId, Function.identity()));
        return page.stream().map(o -> toResponse(o, sagaById.get(o.getId()))).toList();
    }

    private OrderResponse toResponse(Order order) {
        return toResponse(order, sagas.findById(order.getId()).orElse(null));
    }

    private OrderResponse toResponse(Order order, CheckoutSaga saga) {
        return new OrderResponse(order.getId(), order.getStatus().name(),
                saga == null ? null : saga.getState().name(), order.getTotalVnd(),
                order.getItems().stream().map(i -> new ItemResponse(i.getProductId(), i.getProductName(),
                        i.getUnitPriceVnd(), i.getQuantity(), i.getLineTotalVnd())).toList(),
                order.getFailureReason(), order.getCreatedAt(), order.getUpdatedAt());
    }

    public record OrderResponse(UUID orderId, String status, String sagaState, long totalVnd,
                                List<ItemResponse> items, String failureReason, Instant createdAt,
                                Instant updatedAt) {
    }

    public record ItemResponse(UUID productId, String productName, long unitPriceVnd, int quantity,
                               long lineTotalVnd) {
    }
}
