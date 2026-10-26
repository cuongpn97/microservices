package com.webshop.product.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Body của POST/PUT /api/products/manage — thông tin SPU (không gồm SKU và ảnh, có endpoint riêng).
 * PUT ghi đè các field nội dung (bỏ trống = rỗng); riêng slug và active bỏ trống = giữ nguyên.
 *
 * @param slug    bỏ trống → khi tạo: tự sinh từ tên (bỏ dấu tiếng Việt); khi sửa: giữ nguyên (không làm hỏng link cũ).
 * @param options trục biến thể; đổi options bị từ chối nếu làm SKU hiện có không còn hợp lệ.
 * @param active  bỏ trống → khi tạo: false (bản nháp, thêm SKU/ảnh xong mới bật bán); khi sửa: giữ nguyên.
 */
public record ProductRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = 220) @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$", message = "chỉ gồm a-z, 0-9 và dấu -")
        String slug,
        @Size(max = 100) String brand,
        @Size(max = 500) String shortDescription,
        @Size(max = 20000) String description,
        @Valid @Size(max = 50) List<SpecificationRequest> specifications,
        @Valid @Size(max = 3) List<OptionRequest> options,
        Boolean active) {
}
