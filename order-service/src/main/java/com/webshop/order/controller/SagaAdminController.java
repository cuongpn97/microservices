package com.webshop.order.controller;

import com.webshop.order.dto.response.SagaResponse;
import com.webshop.order.service.SagaAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Công cụ vận hành Saga (ROLE_ADMIN, gọi trực tiếp port service — gateway không route /admin). */
@RestController
@RequestMapping("/admin/sagas")
@RequiredArgsConstructor
public class SagaAdminController {

    private final SagaAdminService sagaAdminService;

    @GetMapping("/{sagaId}")
    public SagaResponse get(@PathVariable UUID sagaId) {
        return sagaAdminService.getSaga(sagaId);
    }

    @GetMapping("/attention")
    public List<SagaResponse> needingAttention() {
        return sagaAdminService.getSagasNeedingAttention();
    }

    /** Ép watcher xử lý Saga ngay ở lần quét tới (ví dụ sau khi khắc phục sự cố downstream). */
    @PostMapping("/{sagaId}/retry-now")
    public SagaResponse retryNow(@PathVariable UUID sagaId) {
        return sagaAdminService.retryNow(sagaId);
    }
}
