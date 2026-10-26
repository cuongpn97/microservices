package com.webshop.product.service;

import com.webshop.product.dto.response.ProductDetailResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * Ảnh sản phẩm: file nằm ở object storage, metadata nằm ở DB. Hai hệ thống KHÔNG có transaction chung, nên thứ tự
 * thao tác được chọn để lỗi giữa chừng chỉ để lại file mồ côi (vô hại, dọn sau) chứ không bao giờ để DB trỏ tới file
 * không tồn tại: upload file TRƯỚC rồi mới ghi DB; xóa dòng DB TRƯỚC rồi mới xóa file.
 */
public interface ProductImageService {

    /** Chỉ nhận JPEG/PNG/WebP (kiểm tra bằng nội dung file), tối đa webshop.storage.max-image-size. */
    ProductDetailResponse upload(UUID productId, MultipartFile file, String altText);

    ProductDetailResponse changeAltText(UUID productId, UUID imageId, String altText);

    ProductDetailResponse reorder(UUID productId, List<UUID> imageIds);

    ProductDetailResponse delete(UUID productId, UUID imageId);
}
