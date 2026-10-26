package com.webshop.order.service.impl;

import com.webshop.common.web.ApiException;
import com.webshop.common.web.CorrelationIds;
import com.webshop.order.client.CartClient;
import com.webshop.order.client.ProductClient;
import com.webshop.order.client.dto.CartSnapshot;
import com.webshop.order.client.dto.SkuInfo;
import com.webshop.order.dto.CheckoutResult;
import com.webshop.order.dto.response.OrderResponse;
import com.webshop.order.entity.Order;
import com.webshop.order.entity.OrderItem;
import com.webshop.order.mapper.OrderMapper;
import com.webshop.order.repository.CheckoutSagaRepository;
import com.webshop.order.repository.OrderRepository;
import com.webshop.order.service.CheckoutSagaOrchestrator;
import com.webshop.order.service.CheckoutService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Bước đồng bộ của checkout (trước khi Saga bất đồng bộ chạy):
 * <ol>
 *   <li>Idempotency-Key đã dùng → trả lại đơn cũ (client retry không tạo đơn mới).</li>
 *   <li>Đọc giỏ hàng (Cart Service, bằng token của user) và giá/trạng thái từng SKU (Product Service).
 *       KHÔNG tin giá/tổng tiền từ client.</li>
 *   <li>Một transaction cục bộ: ghi Order (PENDING) + snapshot item + Saga + command ReserveInventory (Outbox).</li>
 * </ol>
 * Các lời gọi HTTP nằm NGOÀI transaction DB (dùng TransactionTemplate thay vì @Transactional để tách được hai phần).
 * Unique index là chốt chặn cuối cho request trùng chạy song song.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutServiceImpl implements CheckoutService {

    private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9_:.-]{8,100}$");

    private final OrderRepository orderRepository;
    private final CheckoutSagaRepository checkoutSagaRepository;
    private final OrderMapper orderMapper;
    private final CartClient cartClient;
    private final ProductClient productClient;
    private final CheckoutSagaOrchestrator checkoutSagaOrchestrator;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Override
    public CheckoutResult checkout(UUID userId, String bearerToken, String idempotencyKey) {
        if (idempotencyKey == null || !KEY_FORMAT.matcher(idempotencyKey).matches()) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key header is required (8-100 chars: letters, digits, _ : . -)");
        }
        Optional<Order> existing = orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            return replayed(existing.get());
        }

        CartSnapshot cart = cartClient.getCart(bearerToken);
        if (cart == null || !userId.equals(cart.userId())) {
            throw new IllegalStateException("Cart service returned a cart for another user");
        }
        if (cart.items() == null || cart.items().isEmpty()) {
            throw ApiException.unprocessable("CART_EMPTY", "Cart is empty");
        }
        List<OrderItem> items = priceItems(cart);

        UUID orderId = UUID.randomUUID();
        String correlationId = CorrelationIds.currentOrNew();
        try {
            Order order = transactionTemplate.execute(status -> {
                Order created = orderRepository.saveAndFlush(new Order(orderId, userId, idempotencyKey,
                        cart.revision(), items, clock.instant()));
                checkoutSagaOrchestrator.start(created, correlationId);
                return created;
            });
            log.info("Checkout accepted orderId={} totalVnd={} items={}", orderId, order.getTotalVnd(), items.size());
            return new CheckoutResult(toResponse(order), false);
        } catch (DataIntegrityViolationException e) {
            // Request trùng chạy song song: bản thắng đã commit trước.
            Optional<Order> winner = orderRepository.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (winner.isPresent()) {
                return replayed(winner.get());
            }
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("ux_orders_active_cart")) {
                throw ApiException.conflict("CHECKOUT_ALREADY_IN_PROGRESS",
                        "This cart revision already has an active order; poll the existing order or modify the cart");
            }
            throw e;
        }
    }

    /** Định giá từng SKU từ Product Service (nguồn có thẩm quyền), không dùng giá trong giỏ hay từ client. */
    private List<OrderItem> priceItems(CartSnapshot cart) {
        Map<UUID, SkuInfo> skus = productClient.findSkus(
                        cart.items().stream().map(CartSnapshot.Line::skuId).toList())
                .stream().collect(Collectors.toMap(SkuInfo::id, Function.identity()));
        List<OrderItem> items = new ArrayList<>();
        for (CartSnapshot.Line line : cart.items()) {
            SkuInfo sku = skus.get(line.skuId());
            if (sku == null || !sku.active()) {
                throw ApiException.unprocessable("PRODUCT_UNAVAILABLE",
                        "Product variant is no longer available: " + line.skuId());
            }
            if (line.quantity() <= 0 || line.quantity() > 99) {
                throw ApiException.unprocessable("INVALID_QUANTITY", "Invalid quantity for " + line.skuId());
            }
            items.add(new OrderItem(sku.id(), sku.productId(), sku.productName(), sku.skuCode(), sku.variantName(),
                    sku.priceVnd(), line.quantity()));
        }
        return items;
    }

    private CheckoutResult replayed(Order order) {
        return new CheckoutResult(toResponse(order), true);
    }

    private OrderResponse toResponse(Order order) {
        return orderMapper.toOrderResponse(order, checkoutSagaRepository.findById(order.getId()).orElse(null));
    }
}
