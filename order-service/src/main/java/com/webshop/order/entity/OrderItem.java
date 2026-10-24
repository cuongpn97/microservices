package com.webshop.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Một dòng của đơn hàng (bảng order_item, @Embeddable — không có id riêng).
 * Snapshot tên và giá TẠI THỜI ĐIỂM checkout: sản phẩm đổi giá sau đó không ảnh hưởng đơn đã đặt.
 */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "unit_price_vnd", nullable = false)
    private long unitPriceVnd;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "line_total_vnd", nullable = false)
    private long lineTotalVnd;

    public OrderItem(UUID productId, String productName, long unitPriceVnd, int quantity) {
        if (quantity <= 0 || unitPriceVnd < 0) {
            throw new IllegalArgumentException("invalid order line");
        }
        this.productId = productId;
        this.productName = productName;
        this.unitPriceVnd = unitPriceVnd;
        this.quantity = quantity;
        // multiplyExact: tràn số → exception thay vì ra tổng tiền sai.
        this.lineTotalVnd = Math.multiplyExact(unitPriceVnd, (long) quantity);
    }
}
