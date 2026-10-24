package com.webshop.order.mapper;

import com.webshop.order.dto.response.OrderItemResponse;
import com.webshop.order.dto.response.OrderResponse;
import com.webshop.order.dto.response.SagaResponse;
import com.webshop.order.dto.response.SagaTransitionResponse;
import com.webshop.order.entity.CheckoutSaga;
import com.webshop.order.entity.Order;
import com.webshop.order.entity.OrderItem;
import com.webshop.order.entity.SagaTransition;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

import java.util.List;

/**
 * Mapper có NHIỀU tham số nguồn (order + saga): Order và CheckoutSaga trùng nhiều tên field (id, totalVnd,
 * failureReason, createdAt, updatedAt...) nên MapStruct không tự đoán được lấy từ đâu → khai báo source rõ ràng
 * cho từng field. saga == null → sagaState = null (MapStruct tự kiểm tra null).
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface OrderMapper {

    @Mapping(target = "orderId", source = "order.id")
    @Mapping(target = "status", source = "order.status")
    @Mapping(target = "sagaState", source = "saga.state")
    @Mapping(target = "totalVnd", source = "order.totalVnd")
    @Mapping(target = "items", source = "order.items")
    @Mapping(target = "failureReason", source = "order.failureReason")
    @Mapping(target = "createdAt", source = "order.createdAt")
    @Mapping(target = "updatedAt", source = "order.updatedAt")
    OrderResponse toOrderResponse(Order order, CheckoutSaga saga);

    OrderItemResponse toOrderItemResponse(OrderItem item);

    @Mapping(target = "sagaId", source = "saga.id")
    @Mapping(target = "inventoryStep", source = "saga.inventory")
    @Mapping(target = "paymentStep", source = "saga.payment")
    @Mapping(target = "state", source = "saga.state")
    @Mapping(target = "stepAttempts", source = "saga.stepAttempts")
    @Mapping(target = "deadlineAt", source = "saga.deadlineAt")
    @Mapping(target = "needsAttention", source = "saga.needsAttention")
    @Mapping(target = "failureReason", source = "saga.failureReason")
    @Mapping(target = "lastError", source = "saga.lastError")
    @Mapping(target = "updatedAt", source = "saga.updatedAt")
    @Mapping(target = "transitions", source = "transitions")
    SagaResponse toSagaResponse(CheckoutSaga saga, List<SagaTransition> transitions);

    SagaTransitionResponse toSagaTransitionResponse(SagaTransition transition);
}
