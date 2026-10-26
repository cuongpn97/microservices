package com.webshop.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Một dòng của đơn hàng (bảng order_item, @Embeddable — không có id riêng).
 * Snapshot tên, biến thể và giá TẠI THỜI ĐIỂM checkout: sản phẩm đổi giá/đổi tên sau đó không ảnh hưởng đơn đã đặt.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

    @Column(name = "sku_id", nullable = false)
    private UUID skuId;

    /** SPU của SKU. null với đơn tạo trước khi có SKU (snapshot bất biến, không thể bổ sung). */
    @Column(name = "product_id")
    private UUID productId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "sku_code")
    private String skuCode;

    /** "Đen / M"; rỗng khi sản phẩm chỉ có một biến thể; null với đơn cũ. */
    @Column(name = "variant_name")
    private String variantName;

    @Column(name = "unit_price_vnd", nullable = false)
    private long unitPriceVnd;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "line_total_vnd", nullable = false)
    private long lineTotalVnd;

    public OrderItem(UUID skuId, UUID productId, String productName, String skuCode, String variantName,
                     long unitPriceVnd, int quantity) {
        if (quantity <= 0 || unitPriceVnd < 0) {
            throw new IllegalArgumentException("invalid order line");
        }
        this.skuId = skuId;
        this.productId = productId;
        this.productName = productName;
        this.skuCode = skuCode;
        this.variantName = variantName;
        this.unitPriceVnd = unitPriceVnd;
        this.quantity = quantity;
        // multiplyExact: tràn số → exception thay vì ra tổng tiền sai.
        this.lineTotalVnd = Math.multiplyExact(unitPriceVnd, (long) quantity);
    }
}
