package com.webshop.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Đọc X-Correlation-Id (gateway đã gắn), đưa vào MDC để mọi dòng log có correlationId,
 * và trả lại trong response. Giá trị từ client được kiểm tra định dạng để tránh log injection.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(CorrelationIds.HEADER);
        String correlationId = incoming != null && SAFE.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();
        MDC.put(CorrelationIds.MDC_CORRELATION_ID, correlationId);
        response.setHeader(CorrelationIds.HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationIds.MDC_CORRELATION_ID);
            MDC.remove(CorrelationIds.MDC_SAGA_ID);
        }
    }
}
