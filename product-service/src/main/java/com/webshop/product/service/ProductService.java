package com.webshop.product.service;

import com.webshop.product.dto.request.ProductRequest;
import com.webshop.product.dto.response.PageResponse;
import com.webshop.product.dto.response.ProductResponse;

import java.util.List;
import java.util.UUID;

public interface ProductService {

    /** Danh sách công khai: chỉ sản phẩm đang bán, sắp theo tên. */
    PageResponse<ProductResponse> listActive(int page, int size);

    /** Trả cả sản phẩm inactive (kèm cờ active) để Cart/Order tự kiểm tra trạng thái. */
    ProductResponse getById(UUID id);

    /** Tra cứu nhiều sản phẩm một lần (Order Service dùng khi checkout); id không tồn tại bị bỏ qua. */
    List<ProductResponse> getByIds(List<UUID> ids);

    ProductResponse create(ProductRequest request);

    /** Đổi giá chỉ ảnh hưởng đơn hàng TƯƠNG LAI: đơn đã checkout giữ snapshot giá tại thời điểm checkout. */
    ProductResponse update(UUID id, ProductRequest request);
}
