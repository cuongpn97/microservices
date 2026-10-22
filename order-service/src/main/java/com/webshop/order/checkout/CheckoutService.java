package com.webshop.order.checkout;

import com.webshop.common.web.ApiException;
import com.webshop.common.web.CorrelationIds;
import com.webshop.order.client.CartClient;
import com.webshop.order.client.ProductClient;
import com.webshop.order.domain.Order;
import com.webshop.order.domain.OrderRepository;
import com.webshop.order.saga.CheckoutSagaOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *   <li>Đọc giỏ hàng (Cart Service, bằng token của user) và giá/trạng thái sản phẩm (Product Service).
 *       KHÔNG tin giá/tổng tiền từ client.</li>
 *   <li>Một transaction cục bộ: ghi Order (PENDING) + snapshot item + Saga + command ReserveInventory (Outbox).</li>
 * </ol>
 * Các lời gọi HTTP nằm NGOÀI transaction DB. Unique index là chốt chặn cuối cho request trùng chạy song song.
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);
    private static final Pattern KEY_FORMAT = Pattern.compile("^[A-Za-z0-9_:.-]{8,100}$");

    private final OrderRepository orders;
    private final CartClient cartClient;
    private final ProductClient productClient;
    private final CheckoutSagaOrchestrator orchestrator;
    private final TransactionTemplate tx;
    private final Clock clock;

    public CheckoutService(OrderRepository orders, CartClient cartClient, ProductClient productClient,
                           CheckoutSagaOrchestrator orchestrator, TransactionTemplate tx, Clock clock) {
        this.orders = orders;
        this.cartClient = cartClient;
        this.productClient = productClient;
        this.orchestrator = orchestrator;
        this.tx = tx;
        this.clock = clock;
    }

    public CheckoutResult checkout(UUID userId, String bearerToken, String idempotencyKey) {
        if (idempotencyKey == null || !KEY_FORMAT.matcher(idempotencyKey).matches()) {
            throw ApiException.badRequest("INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key header is required (8-100 chars: letters, digits, _ : . -)");
        }
        Optional<Order> existing = orders.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            return new CheckoutResult(existing.get(), true);
        }

        CartClient.CartSnapshot cart = cartClient.getCart(bearerToken);
        if (cart == null || !userId.equals(cart.userId())) {
            throw new IllegalStateException("Cart service returned a cart for another user");
        }
        if (cart.items() == null || cart.items().isEmpty()) {
            throw ApiException.unprocessable("CART_EMPTY", "Cart is empty");
        }
        List<Order.Item> items = priceItems(cart);

        UUID orderId = UUID.randomUUID();
        String correlationId = CorrelationIds.currentOrNew();
        try {
            Order order = tx.execute(status -> {
                Order created = orders.saveAndFlush(new Order(orderId, userId, idempotencyKey, cart.revision(), items,
                        clock.instant()));
                orchestrator.start(created, correlationId);
                return created;
            });
            log.info("Checkout accepted orderId={} totalVnd={} items={}", orderId, order.getTotalVnd(), items.size());
            return new CheckoutResult(order, false);
        } catch (DataIntegrityViolationException e) {
            // Request trùng chạy song song: bản thắng đã commit trước.
            Optional<Order> winner = orders.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (winner.isPresent()) {
                return new CheckoutResult(winner.get(), true);
            }
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains("ux_orders_active_cart")) {
                throw ApiException.conflict("CHECKOUT_ALREADY_IN_PROGRESS",
                        "This cart revision already has an active order; poll the existing order or modify the cart");
            }
            throw e;
        }
    }

    private List<Order.Item> priceItems(CartClient.CartSnapshot cart) {
        Map<UUID, ProductClient.ProductInfo> products = productClient.findAll(
                        cart.items().stream().map(CartClient.CartSnapshot.Line::productId).toList())
                .stream().collect(Collectors.toMap(ProductClient.ProductInfo::id, Function.identity()));
        List<Order.Item> items = new ArrayList<>();
        for (CartClient.CartSnapshot.Line line : cart.items()) {
            ProductClient.ProductInfo product = products.get(line.productId());
            if (product == null || !product.active()) {
                throw ApiException.unprocessable("PRODUCT_UNAVAILABLE",
                        "Product is no longer available: " + line.productId());
            }
            if (line.quantity() <= 0 || line.quantity() > 99) {
                throw ApiException.unprocessable("INVALID_QUANTITY", "Invalid quantity for " + line.productId());
            }
            items.add(new Order.Item(product.id(), product.name(), product.priceVnd(), line.quantity()));
        }
        return items;
    }

    public record CheckoutResult(Order order, boolean replayed) {
    }
}
