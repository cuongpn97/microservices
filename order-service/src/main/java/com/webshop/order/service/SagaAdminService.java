package com.webshop.order.service;

import com.webshop.order.dto.response.SagaResponse;

import java.util.List;
import java.util.UUID;

/** Công cụ vận hành Saga — xem docs/failure-scenarios.md. */
public interface SagaAdminService {

    /** Kèm toàn bộ nhật ký chuyển trạng thái. */
    SagaResponse getSaga(UUID sagaId);

    /** Saga bị gắn cờ needs_attention (retry quá ngưỡng hoặc participant từ chối bất thường), mới nhất trước. */
    List<SagaResponse> getSagasNeedingAttention();

    /** Đặt deadline = bây giờ → watcher xử lý Saga ở lần quét tới (ví dụ sau khi khắc phục sự cố downstream). */
    SagaResponse retryNow(UUID sagaId);
}
