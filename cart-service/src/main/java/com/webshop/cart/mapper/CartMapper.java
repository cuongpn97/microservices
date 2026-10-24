package com.webshop.cart.mapper;

import com.webshop.cart.client.dto.ProductInfo;
import com.webshop.cart.dto.response.CartItemResponse;
import com.webshop.cart.dto.response.CartResponse;
import com.webshop.cart.entity.Cart;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mapper viết tay, KHÔNG dùng MapStruct: items trong Cart là Map&lt;productId, quantity&gt; (không phải list object),
 * và mỗi dòng phải ghép thêm giá từ Product Service rồi tính tổng. MapStruct mạnh ở việc chép field cùng tên giữa
 * hai object; logic ghép + tính toán thế này viết tay dễ đọc hơn là nhồi vào @Mapping(expression = ...).
 */
@Component
public class CartMapper {

    /**
     * Giỏ không kèm giá.
     *
     * @param pricingAvailable true chỉ khi giỏ rỗng (tổng = 0 là chính xác); false khi đã cố lấy giá mà thất bại,
     *                         hoặc client không yêu cầu giá.
     */
    public CartResponse toResponseWithoutPricing(Cart cart, boolean pricingAvailable) {
        List<CartItemResponse> items = cart.getItems().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> new CartItemResponse(e.getKey(), e.getValue(), null, null, null, true))
                .toList();
        return new CartResponse(cart.getUserId(), cart.getRevision(), items,
                pricingAvailable && items.isEmpty() ? 0L : null, pricingAvailable, cart.getUpdatedAt());
    }

    /**
     * Giỏ kèm giá hiện tại. Sản phẩm không còn trong Product Service → dòng không có giá và active=false.
     * Tổng tạm tính chỉ cộng các dòng còn bán.
     */
    public CartResponse toResponseWithPricing(Cart cart, Map<UUID, ProductInfo> products) {
        List<CartItemResponse> items = cart.getItems().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    ProductInfo p = products.get(e.getKey());
                    return p == null
                            ? new CartItemResponse(e.getKey(), e.getValue(), null, null, null, false)
                            : new CartItemResponse(e.getKey(), e.getValue(), p.name(), p.priceVnd(),
                            Math.multiplyExact(p.priceVnd(), (long) e.getValue()), p.active());
                })
                .toList();
        long total = items.stream().filter(i -> i.lineTotalVnd() != null && i.active())
                .mapToLong(CartItemResponse::lineTotalVnd).sum();
        return new CartResponse(cart.getUserId(), cart.getRevision(), items, total, true, cart.getUpdatedAt());
    }
}
