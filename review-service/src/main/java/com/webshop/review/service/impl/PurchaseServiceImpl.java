package com.webshop.review.service.impl;

import com.webshop.review.messaging.event.OrderConfirmedEvent;
import com.webshop.review.repository.PurchaseRepository;
import com.webshop.review.service.PurchaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Slf4j
@Service
@RequiredArgsConstructor
public class PurchaseServiceImpl implements PurchaseService {

    private final PurchaseRepository purchaseRepository;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordPurchases(OrderConfirmedEvent event, Instant confirmedAt) {
        int recorded = 0;
        for (OrderConfirmedEvent.Item item : event.items()) {
            if (item.productId() == null) {
                continue; // dòng của đơn tạo trước khi có SKU/SPU — không biết sản phẩm nào
            }
            recorded += purchaseRepository.recordIfAbsent(event.userId(), item.productId(), event.orderId(),
                    item.skuId(), item.variantName() == null ? "" : item.variantName(), confirmedAt);
        }
        log.info("Recorded {} purchase(s) for order {}", recorded, event.orderId());
    }
}
