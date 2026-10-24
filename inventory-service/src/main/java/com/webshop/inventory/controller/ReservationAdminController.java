package com.webshop.inventory.controller;

import com.webshop.inventory.dto.response.ReservationResponse;
import com.webshop.inventory.service.InventoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Endpoint vận hành: tra cứu reservation phục vụ đối soát/can thiệp thủ công.
 * ADMIN (chặn ở SecurityConfig: /admin/**) và không được route qua API Gateway.
 */
@RestController
@RequestMapping("/admin/reservations")
@RequiredArgsConstructor
public class ReservationAdminController {

    private final InventoryService inventoryService;

    @GetMapping("/{reservationId}")
    public ReservationResponse get(@PathVariable UUID reservationId) {
        return inventoryService.getReservation(reservationId);
    }
}
