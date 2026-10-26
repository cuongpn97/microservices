package com.webshop.search.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webshop.search.entity.ProductDocument;
import com.webshop.search.exception.MalformedChangeEventException;
import com.webshop.search.messaging.event.ChangeEvent;
import com.webshop.search.repository.ProductIndexRepository;
import com.webshop.search.service.ProductIndexService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Chiếu thay đổi của bảng catalog_document thành document tìm kiếm: một document = một SPU.
 *
 * <p>Hợp đồng là nội dung cột payload (JSON do product-service định nghĩa, có schema_version), KHÔNG phải tên cột
 * của các bảng product/product_sku — product-service đổi schema nội bộ không làm vỡ search
 * (docs/design-decisions.md mục 13). Field lạ trong payload bị bỏ qua (tolerant reader).</p>
 *
 * <p>Không index tồn kho: xem docs/design-decisions.md mục 12. UI đọc tồn kho thật từ Inventory Service.</p>
 */
@Service
@RequiredArgsConstructor
public class ProductIndexServiceImpl implements ProductIndexService {

    private final ProductIndexRepository productIndexRepository;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Override
    public void apply(ChangeEvent event) {
        String id = event.requiredText("product_id");
        String result = switch (event.op()) {
            case DELETE -> productIndexRepository.markDeleted(id);
            case CREATE, UPDATE, SNAPSHOT_READ -> productIndexRepository.upsert(
                    toDocument(id, event.requiredLong("version"), event.requiredJson(objectMapper, "payload")));
        };
        meterRegistry.counter("webshop.search.cdc.applied", "result", result).increment();
    }

    private static ProductDocument toDocument(String id, long version, JsonNode p) {
        JsonNode name = p.get("name");
        if (name == null || !name.isTextual()) {
            throw new MalformedChangeEventException("catalog_document payload without name for " + id);
        }
        return new ProductDocument(id,
                text(p, "slug"), name.asText(), text(p, "brand"), text(p, "shortDescription"),
                text(p, "description"),
                p.path("active").asBoolean(false),
                longOrNull(p, "minPriceVnd"), longOrNull(p, "maxPriceVnd"),
                text(p, "imageUrl"),
                p.hasNonNull("ratingAvg") ? p.get("ratingAvg").asDouble() : null,
                p.path("ratingCount").asInt(0),
                strings(p, "skuCodes"), strings(p, "optionValues"),
                p.path("sellableSkuCount").asInt(0),
                version,
                text(p, "updatedAt"));
    }

    private static String text(JsonNode p, String field) {
        return p.hasNonNull(field) ? p.get(field).asText() : null;
    }

    private static Long longOrNull(JsonNode p, String field) {
        return p.hasNonNull(field) ? p.get(field).asLong() : null;
    }

    private static List<String> strings(JsonNode p, String field) {
        List<String> values = new ArrayList<>();
        p.path(field).forEach(v -> values.add(v.asText()));
        return values;
    }
}
