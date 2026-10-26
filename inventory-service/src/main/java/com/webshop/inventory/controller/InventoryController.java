package com.webshop.inventory.controller;

import com.webshop.inventory.dto.request.SetStockRequest;
import com.webshop.inventory.dto.response.StockResponse;
import com.webshop.inventory.service.InventoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/inventory")
@Validated
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @GetMapping("/{skuId}")
    public StockResponse get(@PathVariable UUID skuId) {
        return inventoryService.getStock(skuId);
    }

    /** Tồn kho của nhiều SKU một lần (trang sản phẩm/giỏ hàng dùng) — tránh N lời gọi HTTP mỗi lượt tải. */
    @GetMapping("/batch")
    public List<StockResponse> batch(@RequestParam @Size(min = 1, max = 100) List<UUID> ids) {
        return inventoryService.getStocks(ids);
    }

    /** ADMIN — chặn ở SecurityConfig (PUT /api/inventory/**). */
    @PutMapping("/{skuId}")
    public StockResponse setStock(@PathVariable UUID skuId, @Valid @RequestBody SetStockRequest request) {
        return inventoryService.setStock(skuId, request.stockOnHand());
    }
}
