package com.webshop.product.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Object storage cho ảnh sản phẩm (SeaweedFS ở local, S3/R2/GCS ở production — cùng S3 API).
 *
 * @param endpoint      URL product-service dùng để GHI (mạng nội bộ, ví dụ http://object-storage:8333).
 * @param publicBaseUrl URL trình duyệt dùng để ĐỌC ảnh, gồm cả bucket (ví dụ http://localhost:8333/product-images).
 *                      Production thường là CDN đứng trước bucket. Hai URL khác nhau vì trong Docker, "object-storage:8333"
 *                      chỉ phân giải được bên trong mạng compose.
 * @param maxImageSize  giới hạn mỗi ảnh; khớp với spring.servlet.multipart.max-file-size.
 */
@ConfigurationProperties(prefix = "webshop.storage")
public record StorageProperties(
        String endpoint,
        String publicBaseUrl,
        String bucket,
        String region,
        String accessKey,
        String secretKey,
        DataSize maxImageSize) {
}
