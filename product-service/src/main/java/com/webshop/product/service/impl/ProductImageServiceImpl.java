package com.webshop.product.service.impl;

import com.webshop.common.web.ApiException;
import com.webshop.product.config.StorageProperties;
import com.webshop.product.dto.response.ProductDetailResponse;
import com.webshop.product.entity.Product;
import com.webshop.product.entity.ProductImage;
import com.webshop.product.repository.ProductRepository;
import com.webshop.product.service.ProductImageService;
import com.webshop.product.storage.ImageFormat;
import com.webshop.product.storage.ImageStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lời gọi storage (mạng) nằm NGOÀI transaction DB — cùng nguyên tắc với lời gọi HTTP ở Cart/Order: không giữ
 * connection và khóa dòng SPU trong lúc chờ upload. Vì vậy dùng TransactionTemplate thay cho @Transactional.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductImageServiceImpl implements ProductImageService {

    private final ProductRepository productRepository;
    private final ProductWriter productWriter;
    private final ImageStorage imageStorage;
    private final StorageProperties storageProperties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    @Override
    public ProductDetailResponse upload(UUID productId, MultipartFile file, String altText) {
        byte[] data = readValidated(file);
        ImageFormat format = ImageFormat.detect(data).orElseThrow(() -> ApiException.unprocessable(
                "UNSUPPORTED_IMAGE", "Only JPEG, PNG and WebP images are accepted"));
        Product current = productRepository.findById(productId).orElseThrow(() -> ProductWriter.notFound(productId));
        if (productRepository.countImages(productId) >= Product.MAX_IMAGES) {
            // Kiểm tra sớm để không upload file rồi lại phải xóa; aggregate vẫn kiểm tra lại trong transaction.
            throw ApiException.unprocessable("INVALID_CATALOG_CHANGE",
                    "a product can have at most " + Product.MAX_IMAGES + " images");
        }

        UUID imageId = UUID.randomUUID();
        // Key mới cho mỗi lần upload, không ghi đè file cũ → ảnh cache được vĩnh viễn (immutable).
        String objectKey = "products/" + productId + "/" + imageId + "." + format.extension();
        String alt = altText == null || altText.isBlank() ? current.getName() : altText.strip();

        imageStorage.put(objectKey, data, format.contentType());
        try {
            return transactionTemplate.execute(status -> productWriter.change(productId, product ->
                    product.addImage(imageId, objectKey, format.contentType(), data.length, alt, clock.instant())));
        } catch (RuntimeException e) {
            // Ghi DB thất bại → file vừa upload thành mồ côi; dọn ngay (best-effort, lỗi thì để job dọn sau).
            deleteQuietly(objectKey);
            throw e;
        }
    }

    @Override
    public ProductDetailResponse changeAltText(UUID productId, UUID imageId, String altText) {
        return transactionTemplate.execute(status -> productWriter.change(productId,
                product -> product.changeImageAltText(imageId, altText.strip(), clock.instant())));
    }

    @Override
    public ProductDetailResponse reorder(UUID productId, List<UUID> imageIds) {
        return transactionTemplate.execute(status -> productWriter.change(productId,
                product -> product.reorderImages(imageIds, clock.instant())));
    }

    @Override
    public ProductDetailResponse delete(UUID productId, UUID imageId) {
        AtomicReference<String> removedKey = new AtomicReference<>();
        ProductDetailResponse result = transactionTemplate.execute(status -> productWriter.change(productId,
                product -> {
                    ProductImage removed = product.removeImage(imageId, clock.instant());
                    removedKey.set(removed.getObjectKey());
                }));
        // Chỉ xóa file SAU khi DB đã commit: rollback thì ảnh vẫn còn nguyên, không có dòng DB trỏ tới file đã mất.
        deleteQuietly(removedKey.get());
        return result;
    }

    private byte[] readValidated(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("EMPTY_FILE", "file is required");
        }
        if (file.getSize() > storageProperties.maxImageSize().toBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                    "Image must be at most " + storageProperties.maxImageSize());
        }
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void deleteQuietly(String objectKey) {
        try {
            imageStorage.delete(objectKey);
        } catch (RuntimeException e) {
            log.warn("Could not delete object {} (orphan left in storage): {}", objectKey, e.getMessage());
        }
    }
}
