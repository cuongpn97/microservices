package com.webshop.product.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SKU — đơn vị bán được: một tổ hợp giá trị option của SPU, có giá và tồn kho riêng (tồn kho ở Inventory Service).
 * Cart/Order/Inventory chỉ biết skuId. Chỉ tạo/sửa qua {@link Product} để aggregate kiểm tra thuộc tính hợp lệ.
 *
 * <p>Không có thao tác xóa: SKU đã nằm trong đơn hàng/tồn kho của service khác → chỉ ngừng bán ({@code active=false}).</p>
 */
@Entity
@Table(name = "product_sku")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductSku {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(name = "sku_code", nullable = false)
    private String skuCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, String> attributes = new LinkedHashMap<>();

    /** Giá theo VND, kiểu long (số nguyên). Không bao giờ dùng double/float cho tiền. */
    @Column(name = "price_vnd", nullable = false)
    private long priceVnd;

    @Column(name = "compare_at_price_vnd")
    private Long compareAtPriceVnd;

    @Column(name = "image_id")
    private UUID imageId;

    @Column(nullable = false)
    private boolean active;

    @Column(nullable = false)
    private int position;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    ProductSku(UUID id, Product product, int position, Instant now) {
        this.id = id;
        this.product = product;
        this.position = position;
        this.createdAt = now;
    }

    void apply(String skuCode, Map<String, String> attributes, long priceVnd, Long compareAtPriceVnd, UUID imageId,
               boolean active, Instant now) {
        if (priceVnd < 0) {
            throw new IllegalArgumentException("priceVnd must be >= 0");
        }
        if (compareAtPriceVnd != null && compareAtPriceVnd <= priceVnd) {
            throw new IllegalArgumentException("compareAtPriceVnd must be greater than priceVnd");
        }
        this.skuCode = skuCode;
        this.attributes = new LinkedHashMap<>(attributes);
        this.priceVnd = priceVnd;
        this.compareAtPriceVnd = compareAtPriceVnd;
        this.imageId = imageId;
        this.active = active;
        this.updatedAt = now;
    }

    void detachImage(UUID removedImageId) {
        if (Objects.equals(imageId, removedImageId)) {
            imageId = null;
        }
    }

    /** Bán được khi cả SKU lẫn SPU đang bật. Cart/Order chỉ cho mua SKU sellable. */
    public boolean isSellable() {
        return active && product.isActive();
    }

    /** Tên biến thể theo thứ tự option của SPU, ví dụ "Đen / M". Rỗng khi SPU không có option. */
    public String getVariantName() {
        return product.getOptions().stream()
                .map(o -> attributes.get(o.name()))
                .filter(Objects::nonNull)
                .collect(Collectors.joining(" / "));
    }
}
