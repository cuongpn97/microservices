package com.webshop.product.mapper;

import com.webshop.product.dto.response.ImageResponse;
import com.webshop.product.dto.response.OptionResponse;
import com.webshop.product.dto.response.PageResponse;
import com.webshop.product.dto.response.ProductDetailResponse;
import com.webshop.product.dto.response.ProductSummaryResponse;
import com.webshop.product.dto.response.SkuInfoResponse;
import com.webshop.product.dto.response.SkuResponse;
import com.webshop.product.dto.response.SpecificationResponse;
import com.webshop.product.entity.Product;
import com.webshop.product.entity.ProductImage;
import com.webshop.product.entity.ProductSku;
import com.webshop.product.storage.ImageStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Mapper viết tay, KHÔNG dùng MapStruct: phần lớn field là giá trị TÍNH (giá min/max qua các SKU, URL ảnh ghép từ
 * object key + base URL của storage, tên biến thể, điểm trung bình). MapStruct mạnh ở chép field cùng tên; nhồi chừng
 * ấy logic vào @Mapping(expression = ...) khó đọc hơn viết thẳng (giống CartMapper ở cart-service).
 */
@Component
@RequiredArgsConstructor
public class CatalogMapper {

    private final ImageStorage imageStorage;

    public PageResponse<ProductSummaryResponse> toPage(Page<Product> page) {
        return new PageResponse<>(page.getContent().stream().map(this::toSummary).toList(), page.getNumber(),
                page.getSize(), page.getTotalElements());
    }

    /** Khoảng giá tính trên các SKU đang bật (không xét SPU active) để backoffice vẫn thấy giá của bản nháp. */
    public ProductSummaryResponse toSummary(Product p) {
        LongSummaryStatistics prices = priceStats(p, ProductSku::isActive);
        return new ProductSummaryResponse(p.getId(), p.getSlug(), p.getName(), p.getBrand(), p.getShortDescription(),
                prices.getCount() == 0 ? null : prices.getMin(), prices.getCount() == 0 ? null : prices.getMax(),
                p.getPrimaryImage().map(this::url).orElse(null), p.getRatingAverage(), p.getRatingCount(),
                p.getSkus().size(), p.isActive(), p.getUpdatedAt());
    }

    /** @param onlySellableSkus true cho storefront (ẩn SKU ngừng bán), false cho backoffice. */
    public ProductDetailResponse toDetail(Product p, boolean onlySellableSkus) {
        List<ProductSku> skus = onlySellableSkus ? p.getSellableSkus() : p.getSkus();
        return new ProductDetailResponse(p.getId(), p.getSlug(), p.getName(), p.getBrand(), p.getShortDescription(),
                p.getDescription(),
                p.getSpecifications().stream().map(s -> new SpecificationResponse(s.name(), s.value())).toList(),
                p.getOptions().stream().map(o -> new OptionResponse(o.name(), o.values())).toList(),
                skus.stream().map(s -> toSkuResponse(p, s)).toList(),
                p.getImages().stream().map(this::toImageResponse).toList(),
                p.getRatingAverage(), p.getRatingCount(), p.isActive(), p.getCreatedAt(), p.getUpdatedAt(),
                p.getVersion());
    }

    public SkuInfoResponse toSkuInfo(ProductSku sku) {
        Product p = sku.getProduct();
        String imageUrl = skuImage(p, sku.getImageId());
        if (imageUrl == null) {
            imageUrl = p.getPrimaryImage().map(this::url).orElse(null);
        }
        return new SkuInfoResponse(sku.getId(), p.getId(), p.getSlug(), p.getName(), sku.getSkuCode(),
                sku.getVariantName(), sku.getPriceVnd(), imageUrl, sku.isSellable());
    }

    public ImageResponse toImageResponse(ProductImage image) {
        return new ImageResponse(image.getId(), url(image), image.getAltText(), image.getPosition(),
                image.getContentType(), image.getSizeBytes());
    }

    public String url(ProductImage image) {
        return imageStorage.publicUrl(image.getObjectKey());
    }

    public LongSummaryStatistics priceStats(Product p, Predicate<ProductSku> filter) {
        return p.getSkus().stream().filter(filter).mapToLong(ProductSku::getPriceVnd).summaryStatistics();
    }

    private SkuResponse toSkuResponse(Product p, ProductSku sku) {
        return new SkuResponse(sku.getId(), sku.getSkuCode(), sku.getAttributes(), sku.getVariantName(),
                sku.getPriceVnd(), sku.getCompareAtPriceVnd(), sku.getImageId(), skuImage(p, sku.getImageId()),
                sku.isActive(), sku.isSellable());
    }

    private String skuImage(Product p, UUID imageId) {
        if (imageId == null) {
            return null;
        }
        return p.getImages().stream().filter(i -> Objects.equals(i.getId(), imageId)).findFirst()
                .map(this::url).orElse(null);
    }
}
