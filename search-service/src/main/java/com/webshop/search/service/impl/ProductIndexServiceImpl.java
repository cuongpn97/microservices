package com.webshop.search.service.impl;

import com.webshop.search.entity.ProductDocument;
import com.webshop.search.messaging.event.ChangeEvent;
import com.webshop.search.repository.ProductIndexRepository;
import com.webshop.search.service.ProductIndexService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Chiếu (project) thay đổi của bảng products thành document tìm kiếm: một document = một sản phẩm.
 *
 * <p>Ánh xạ cột: tên cột SQL (snake_case) là hợp đồng với product-service. Đổi tên cột ở đó là breaking change với
 * search-service — cái giá của CDC trên bảng thô (xem docs/design-decisions.md).</p>
 *
 * <p>Không index tồn kho: search không lọc/sắp xếp theo tồn kho, nên index nó chỉ tốn một lần index lại document cho
 * MỖI reserve/commit/release (Elasticsearch không cập nhật tại chỗ) — đúng lúc flash sale. Số tồn kho hiển thị được
 * UI đọc thẳng từ Inventory Service cho các sản phẩm đang hiển thị.</p>
 */
@Service
@RequiredArgsConstructor
public class ProductIndexServiceImpl implements ProductIndexService {

    private final ProductIndexRepository productIndexRepository;
    private final MeterRegistry meterRegistry;

    @Override
    public void apply(ChangeEvent event) {
        String id = event.requiredText("id");
        String result = switch (event.op()) {
            case DELETE -> productIndexRepository.markDeleted(id);
            case CREATE, UPDATE, SNAPSHOT_READ -> productIndexRepository.upsert(new ProductDocument(
                    id,
                    event.requiredText("name"),
                    event.row().path("description").asText(""),
                    event.requiredLong("price_vnd"),
                    event.requiredBoolean("active"),
                    event.requiredLong("version"),
                    event.requiredText("updated_at")));
        };
        meterRegistry.counter("webshop.search.cdc.applied", "result", result).increment();
    }
}
