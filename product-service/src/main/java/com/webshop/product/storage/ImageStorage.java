package com.webshop.product.storage;

/**
 * Nơi lưu file ảnh. Interface để test thay bằng bản trong bộ nhớ, và để đổi nhà cung cấp mà không đụng service.
 * Lỗi kết nối được ném ra như {@link com.webshop.common.http.DependencyUnavailableException} (→ 503).
 */
public interface ImageStorage {

    void put(String objectKey, byte[] data, String contentType);

    /** Xóa không tồn tại thì coi như thành công (idempotent). */
    void delete(String objectKey);

    /** URL công khai để trình duyệt tải ảnh. */
    String publicUrl(String objectKey);
}
