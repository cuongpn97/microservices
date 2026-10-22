package com.webshop.common.web;

import java.time.Instant;
import java.util.List;

/** Định dạng lỗi thống nhất cho mọi service. */
public record ApiError(Instant timestamp, int status, String code, String message, String correlationId,
                       List<FieldViolation> violations) {

    public record FieldViolation(String field, String message) {
    }
}
