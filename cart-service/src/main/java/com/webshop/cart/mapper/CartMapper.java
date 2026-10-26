package com.webshop.cart.mapper;

import com.webshop.cart.client.dto.SkuInfo;
import com.webshop.cart.dto.response.CartItemResponse;
import com.webshop.cart.dto.response.CartResponse;
import com.webshop.cart.entity.Cart;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mapper viết tay, KHÔNG dùng MapStruct: items trong Cart là Map&lt;skuId, quantity&gt; (không phải list object),
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
                .map(e -> unpriced(e.getKey(), e.getValue(), true))
                .toList();
        return new CartResponse(cart.getUserId(), cart.getRevision(), items,
                pricingAvailable && items.isEmpty() ? 0L : null, pricingAvailable, cart.getUpdatedAt());
    }

    /**
     * Giỏ kèm giá hiện tại. SKU không còn trong Product Service → dòng không có giá và active=false.
     * Tổng tạm tính chỉ cộng các dòng còn bán.
     */
    public CartResponse toResponseWithPricing(Cart cart, Map<UUID, SkuInfo> skus) {
        List<CartItemResponse> items = cart.getItems().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    SkuInfo s = skus.get(e.getKey());
                    return s == null
                            ? unpriced(e.getKey(), e.getValue(), false)
                            : new CartItemResponse(e.getKey(), e.getValue(), s.productId(), s.productSlug(),
                            s.productName(), s.skuCode(), s.variantName(), s.imageUrl(), s.priceVnd(),
                            Math.multiplyExact(s.priceVnd(), (long) e.getValue()), s.active());
                })
                .toList();
        long total = items.stream().filter(i -> i.lineTotalVnd() != null && i.active())
                .mapToLong(CartItemResponse::lineTotalVnd).sum();
        return new CartResponse(cart.getUserId(), cart.getRevision(), items, total, true, cart.getUpdatedAt());
    }

    private static CartItemResponse unpriced(UUID skuId, int quantity, boolean active) {
        return new CartItemResponse(skuId, quantity, null, null, null, null, null, null, null, null, active);
    }
}
