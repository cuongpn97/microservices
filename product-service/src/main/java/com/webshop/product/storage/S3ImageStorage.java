package com.webshop.product.storage;

import com.webshop.common.http.DependencyUnavailableException;
import com.webshop.product.config.StorageProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lưu ảnh qua S3 API (SeaweedFS ở local, S3/R2/GCS ở production). Bucket cho phép ĐỌC công khai (GetObject) để
 * trình duyệt tải ảnh trực tiếp, không đi qua service — ảnh là dữ liệu công khai, và tải ảnh qua Java service chỉ tốn thread/băng thông.
 * GHI chỉ product-service làm được (có access key), sau khi đã kiểm tra quyền ADMIN và định dạng file.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class S3ImageStorage implements ImageStorage {

    private static final String DEPENDENCY = "object-storage";

    private final S3Client s3;
    private final StorageProperties props;
    private final AtomicBoolean bucketReady = new AtomicBoolean();

    /**
     * Tạo bucket + policy đọc công khai lúc khởi động. Storage chưa sẵn sàng KHÔNG làm service chết (đọc catalog vẫn
     * chạy được) — chỉ log cảnh báo, lần upload đầu tiên sẽ thử lại.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void prepareBucket() {
        try {
            ensureBucket();
        } catch (DependencyUnavailableException e) {
            log.warn("Object storage not ready at startup, will retry on first upload: {}", e.getMessage());
        }
    }

    @Override
    public void put(String objectKey, byte[] data, String contentType) {
        ensureBucket();
        call(() -> s3.putObject(r -> r.bucket(props.bucket()).key(objectKey).contentType(contentType)
                // Ảnh không bao giờ sửa tại chỗ (key mới cho mỗi lần upload) → cache lâu dài an toàn.
                .cacheControl("public, max-age=31536000, immutable"), RequestBody.fromBytes(data)));
    }

    @Override
    public void delete(String objectKey) {
        call(() -> s3.deleteObject(r -> r.bucket(props.bucket()).key(objectKey)));
    }

    @Override
    public String publicUrl(String objectKey) {
        return props.publicBaseUrl() + "/" + objectKey;
    }

    private void ensureBucket() {
        if (bucketReady.get()) {
            return;
        }
        call(() -> {
            try {
                s3.headBucket(r -> r.bucket(props.bucket()));
            } catch (NoSuchBucketException e) {
                try {
                    s3.createBucket(r -> r.bucket(props.bucket()));
                    log.info("Created bucket {}", props.bucket());
                } catch (BucketAlreadyOwnedByYouException ignored) {
                    // instance khác vừa tạo
                }
            }
            allowPublicRead();
            return null;
        });
        bucketReady.set(true);
    }

    /**
     * Chỉ cho đọc (s3:GetObject) từng object; KHÔNG cho liệt kê bucket (s3:ListBucket) hay ghi. Best-effort: một số
     * storage cấu hình quyền đọc ẩn danh ở phía server (SeaweedFS local: identity "anonymous" trong
     * infra/seaweedfs/start.sh) hoặc không hỗ trợ bucket policy — khi đó chỉ cảnh báo, không chặn upload.
     */
    private void allowPublicRead() {
        try {
            s3.putBucketPolicy(r -> r.bucket(props.bucket()).policy("""
                    {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["*"]},
                     "Action":["s3:GetObject"],"Resource":["arn:aws:s3:::%s/*"]}]}
                    """.formatted(props.bucket())));
        } catch (S3Exception e) {
            log.warn("Bucket policy not applied ({}); make sure anonymous read is configured on the storage side",
                    e.awsErrorDetails() == null ? e.getMessage() : e.awsErrorDetails().errorCode());
        }
    }

    private static <T> T call(java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (SdkException e) {
            throw new DependencyUnavailableException(DEPENDENCY, e.getMessage(), e);
        }
    }
}
