package com.webshop.product.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * SPU (Standard Product Unit) — aggregate root của catalog: thông tin chung, ảnh, thông số, các trục biến thể
 * (options) và danh sách SKU. Mọi thay đổi SKU/ảnh đi qua class này để kiểm tra bất biến ở MỘT chỗ:
 * <ul>
 *   <li>thuộc tính của SKU khớp đúng các option (đủ trục, giá trị nằm trong danh sách);</li>
 *   <li>không có hai SKU cùng tổ hợp thuộc tính;</li>
 *   <li>ảnh gắn cho SKU phải là ảnh của chính SPU này.</li>
 * </ul>
 * Mỗi thay đổi gọi {@link #touch} → {@code version} (@Version) của dòng product luôn tăng, kể cả khi chỉ SKU/ảnh đổi.
 * Nhờ vậy hai admin sửa cùng sản phẩm không ghi đè nhau âm thầm, và catalog_document luôn được ghi lại.
 */
@Entity
@Table(name = "product")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    public static final int MAX_OPTIONS = 3;
    public static final int MAX_IMAGES = 10;

    @Id
    private UUID id;

    @Column(nullable = false)
    private String slug;

    @Column(nullable = false)
    private String name;

    private String brand;

    @Column(name = "short_description", nullable = false)
    private String shortDescription;

    @Column(nullable = false)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<Specification> specifications = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private List<ProductOption> options = new ArrayList<>();

    @Column(nullable = false)
    private boolean active;

    @Column(name = "rating_count", nullable = false)
    private int ratingCount;

    @Column(name = "rating_sum", nullable = false)
    private long ratingSum;

    @Column(name = "rating_version", nullable = false)
    private long ratingVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL)
    @OrderBy("position ASC, createdAt ASC")
    @Getter(AccessLevel.NONE)
    private List<ProductSku> skus = new ArrayList<>();

    @OneToMany(mappedBy = "product", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    @Getter(AccessLevel.NONE)
    private List<ProductImage> images = new ArrayList<>();

    public Product(UUID id, String slug, Instant now) {
        this.id = id;
        this.slug = slug;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Ghi đè toàn bộ thông tin SPU. Đổi options bị từ chối nếu làm SKU hiện có trở thành không hợp lệ. */
    public void updateDetails(String slug, String name, String brand, String shortDescription, String description,
                              List<Specification> specifications, List<ProductOption> options, boolean active,
                              Instant now) {
        validateOptions(options);
        for (ProductSku sku : skus) {
            String problem = attributeProblem(options, sku.getAttributes());
            if (problem != null) {
                throw new IllegalArgumentException("options would invalidate SKU " + sku.getSkuCode() + ": " + problem);
            }
        }
        this.slug = slug;
        this.name = name;
        this.brand = brand;
        this.shortDescription = shortDescription;
        this.description = description;
        this.specifications = new ArrayList<>(specifications);
        this.options = new ArrayList<>(options);
        this.active = active;
        touch(now);
    }

    // ------------------------------------------------------------------ SKU

    public ProductSku addSku(UUID skuId, String skuCode, Map<String, String> attributes, long priceVnd,
                             Long compareAtPriceVnd, UUID imageId, boolean active, Instant now) {
        validateSku(null, attributes, imageId);
        ProductSku sku = new ProductSku(skuId, this, skus.size(), now);
        sku.apply(skuCode, attributes, priceVnd, compareAtPriceVnd, imageId, active, now);
        skus.add(sku);
        touch(now);
        return sku;
    }

    public ProductSku updateSku(UUID skuId, String skuCode, Map<String, String> attributes, long priceVnd,
                                Long compareAtPriceVnd, UUID imageId, boolean active, Instant now) {
        ProductSku sku = findSku(skuId)
                .orElseThrow(() -> new IllegalArgumentException("SKU " + skuId + " does not belong to product " + id));
        validateSku(sku, attributes, imageId);
        sku.apply(skuCode, attributes, priceVnd, compareAtPriceVnd, imageId, active, now);
        touch(now);
        return sku;
    }

    public Optional<ProductSku> findSku(UUID skuId) {
        return skus.stream().filter(s -> s.getId().equals(skuId)).findFirst();
    }

    public List<ProductSku> getSkus() {
        return Collections.unmodifiableList(skus);
    }

    public List<ProductSku> getSellableSkus() {
        return skus.stream().filter(ProductSku::isSellable).toList();
    }

    // ------------------------------------------------------------------ Ảnh

    public ProductImage addImage(UUID imageId, String objectKey, String contentType, long sizeBytes, String altText,
                                 Instant now) {
        if (images.size() >= MAX_IMAGES) {
            throw new IllegalArgumentException("a product can have at most " + MAX_IMAGES + " images");
        }
        ProductImage image = new ProductImage(imageId, this, objectKey, contentType, sizeBytes, altText,
                images.size(), now);
        images.add(image);
        touch(now);
        return image;
    }

    /** @return ảnh đã gỡ (để service xóa file trong storage SAU khi transaction commit). */
    public ProductImage removeImage(UUID imageId, Instant now) {
        ProductImage image = findImage(imageId);
        images.remove(image);
        skus.forEach(s -> s.detachImage(imageId));
        renumberImages();
        touch(now);
        return image;
    }

    public void changeImageAltText(UUID imageId, String altText, Instant now) {
        findImage(imageId).changeAltText(altText);
        touch(now);
    }

    /** imageIds phải là hoán vị đầy đủ của các ảnh hiện có; ảnh đầu tiên là ảnh đại diện. */
    public void reorderImages(List<UUID> imageIds, Instant now) {
        Set<UUID> current = new HashSet<>(images.stream().map(ProductImage::getId).toList());
        if (imageIds.size() != images.size() || !current.equals(new HashSet<>(imageIds))) {
            throw new IllegalArgumentException("imageIds must list every image of the product exactly once");
        }
        for (int i = 0; i < imageIds.size(); i++) {
            findImage(imageIds.get(i)).moveTo(i);
        }
        images.sort((a, b) -> Integer.compare(a.getPosition(), b.getPosition()));
        touch(now);
    }

    public List<ProductImage> getImages() {
        return Collections.unmodifiableList(images);
    }

    public Optional<ProductImage> getPrimaryImage() {
        return images.stream().findFirst();
    }

    // ------------------------------------------------------------------ Rating (bản sao từ review-service)

    /**
     * Áp dụng tóm tắt đánh giá mới. Event có thể đến trễ/đảo thứ tự (retry queue) → chỉ nhận bản có version lớn hơn.
     * Không chạm updatedAt: đây không phải chỉnh sửa của admin.
     *
     * @return false nếu bị bỏ qua vì cũ hơn bản đang có.
     */
    public boolean applyRating(int count, long sum, long ratingVersion) {
        if (ratingVersion <= this.ratingVersion) {
            return false;
        }
        this.ratingCount = count;
        this.ratingSum = sum;
        this.ratingVersion = ratingVersion;
        return true;
    }

    /** Điểm trung bình làm tròn 1 chữ số; null khi chưa có đánh giá. */
    public Double getRatingAverage() {
        return ratingCount == 0 ? null : Math.round(ratingSum * 10.0 / ratingCount) / 10.0;
    }

    // ------------------------------------------------------------------ nội bộ

    private void touch(Instant now) {
        this.updatedAt = now;
    }

    private ProductImage findImage(UUID imageId) {
        return images.stream().filter(i -> i.getId().equals(imageId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("image " + imageId + " does not belong to product " + id));
    }

    private void renumberImages() {
        for (int i = 0; i < images.size(); i++) {
            images.get(i).moveTo(i);
        }
    }

    private void validateSku(ProductSku self, Map<String, String> attributes, UUID imageId) {
        String problem = attributeProblem(options, attributes);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        boolean duplicate = skus.stream().anyMatch(s -> s != self && s.getAttributes().equals(attributes));
        if (duplicate) {
            throw new IllegalArgumentException("another SKU already has attributes " + attributes);
        }
        if (imageId != null && images.stream().noneMatch(i -> i.getId().equals(imageId))) {
            throw new IllegalArgumentException("image " + imageId + " does not belong to product " + id);
        }
    }

    /** null nếu attributes khớp options: đúng tập tên option, mỗi giá trị nằm trong danh sách của option đó. */
    private static String attributeProblem(List<ProductOption> options, Map<String, String> attributes) {
        Set<String> names = new HashSet<>(options.stream().map(ProductOption::name).toList());
        if (!names.equals(attributes.keySet())) {
            return "SKU attributes " + attributes.keySet() + " must match product options " + names;
        }
        for (ProductOption option : options) {
            if (!option.values().contains(attributes.get(option.name()))) {
                return "value '" + attributes.get(option.name()) + "' is not defined for option " + option.name();
            }
        }
        return null;
    }

    private static void validateOptions(List<ProductOption> options) {
        if (options.size() > MAX_OPTIONS) {
            throw new IllegalArgumentException("a product can have at most " + MAX_OPTIONS + " options");
        }
        Set<String> names = new HashSet<>();
        for (ProductOption option : options) {
            if (!names.add(option.name())) {
                throw new IllegalArgumentException("duplicate option " + option.name());
            }
            if (option.values().isEmpty() || new HashSet<>(option.values()).size() != option.values().size()) {
                throw new IllegalArgumentException("option " + option.name() + " needs distinct, non-empty values");
            }
        }
    }
}
