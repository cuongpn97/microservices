package com.webshop.order.service.impl;

import com.webshop.common.web.ApiException;
import com.webshop.order.dto.response.OrderResponse;
import com.webshop.order.entity.CheckoutSaga;
import com.webshop.order.entity.Order;
import com.webshop.order.mapper.OrderMapper;
import com.webshop.order.repository.CheckoutSagaRepository;
import com.webshop.order.repository.OrderRepository;
import com.webshop.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderServiceImpl implements OrderService {

    private static final int MY_ORDERS_LIMIT = 50;

    private final OrderRepository orderRepository;
    private final CheckoutSagaRepository checkoutSagaRepository;
    private final OrderMapper orderMapper;

    @Override
    public OrderResponse getOrder(UUID userId, UUID orderId) {
        Order order = orderRepository.findById(orderId)
                .filter(o -> o.getUserId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("ORDER_NOT_FOUND", "Order not found"));
        return orderMapper.toOrderResponse(order, checkoutSagaRepository.findById(orderId).orElse(null));
    }

    @Override
    public List<OrderResponse> getMyOrders(UUID userId) {
        List<Order> page = orderRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, MY_ORDERS_LIMIT));
        // Một câu IN cho saga của cả trang thay vì findById trong vòng lặp (N+1).
        Map<UUID, CheckoutSaga> sagaById = checkoutSagaRepository.findAllById(page.stream().map(Order::getId).toList())
                .stream().collect(Collectors.toMap(CheckoutSaga::getId, Function.identity()));
        return page.stream().map(o -> orderMapper.toOrderResponse(o, sagaById.get(o.getId()))).toList();
    }
}
