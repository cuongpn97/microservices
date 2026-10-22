package com.webshop.common.web;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Tên header/MDC key dùng chung để truy vết một request hoặc một luồng Saga qua nhiều service.
 * correlationId: một "chuỗi nghiệp vụ" (ví dụ: một lần checkout) — giữ nguyên qua HTTP và message.
 * sagaId: định danh Saga (trùng orderId) — chỉ có trong luồng checkout.
 */
public final class CorrelationIds {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_CORRELATION_ID = "correlationId";
    public static final String MDC_SAGA_ID = "sagaId";

    private CorrelationIds() {
    }

    public static String currentOrNew() {
        String current = MDC.get(MDC_CORRELATION_ID);
        return current != null ? current : UUID.randomUUID().toString();
    }

    public static String current() {
        return MDC.get(MDC_CORRELATION_ID);
    }
}
