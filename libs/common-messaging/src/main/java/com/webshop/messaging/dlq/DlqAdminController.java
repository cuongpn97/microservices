package com.webshop.messaging.dlq;

import com.webshop.common.web.ApiException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoint vận hành DLQ: /admin/** yêu cầu ROLE_ADMIN (cấu hình trong SecurityFilterChain từng service)
 * và KHÔNG được gateway route ra ngoài.
 */
@RestController
@RequestMapping("/admin/dlq")
public class DlqAdminController {

    private final DlqService dlqService;

    public DlqAdminController(DlqService dlqService) {
        this.dlqService = dlqService;
    }

    /**
     * Tên DLQ → số message đang nằm trong đó. Giữ Map ở đây là đúng: key là tên queue (khác nhau theo service),
     * không phải một tập field cố định để khai báo thành record.
     */
    @GetMapping
    public Map<String, Long> depths() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String dlq : dlqService.ownedDlqs()) {
            result.put(dlq, dlqService.depth(dlq));
        }
        return result;
    }

    @GetMapping("/{dlq}/messages")
    public List<DlqMessage> peek(@PathVariable String dlq, @RequestParam(defaultValue = "10") int max) {
        try {
            return dlqService.peek(dlq, Math.min(max, 100));
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("DLQ_NOT_FOUND", e.getMessage());
        }
    }

    @PostMapping("/{dlq}/redrive")
    public RedriveResult redrive(@PathVariable String dlq, @RequestParam(defaultValue = "10") int max) {
        try {
            int moved = dlqService.redrive(dlq, Math.min(max, 1000));
            return new RedriveResult(dlq, moved);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("DLQ_NOT_FOUND", e.getMessage());
        }
    }
}
