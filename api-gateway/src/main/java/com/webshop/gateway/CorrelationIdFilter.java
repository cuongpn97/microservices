package com.webshop.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gắn X-Correlation-Id cho mọi request đi qua gateway (giữ giá trị hợp lệ từ client, nếu không thì sinh mới),
 * chuyển tiếp xuống service và trả về trong response. Ghi access log KHÔNG chứa token/body.
 */
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    public static final String HEADER = "X-Correlation-Id";
    private static final Logger log = LoggerFactory.getLogger("gateway.access");
    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String correlationId = incoming != null && SAFE.matcher(incoming).matches()
                ? incoming : UUID.randomUUID().toString();
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(h -> h.set(HEADER, correlationId))
                .build();
        exchange.getResponse().getHeaders().set(HEADER, correlationId);
        long start = System.nanoTime();
        return chain.filter(exchange.mutate().request(request).build())
                .doFinally(signal -> log.info("method={} path={} status={} durationMs={} correlationId={}",
                        request.getMethod(), request.getPath().value(),
                        exchange.getResponse().getStatusCode() == null ? null
                                : exchange.getResponse().getStatusCode().value(),
                        (System.nanoTime() - start) / 1_000_000, correlationId));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
