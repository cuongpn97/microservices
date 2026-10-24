package com.webshop.order.service;

import com.webshop.messaging.EventEnvelope;
import com.webshop.order.entity.CheckoutSaga;
import com.webshop.order.entity.Order;
import com.webshop.order.messaging.event.SagaReplyEvent;

/**
 * Điều phối Saga checkout: nhận một tác nhân (bắt đầu / reply / timeout) → hỏi máy trạng thái {@link CheckoutSaga}
 * nên làm gì → lưu kết quả và phát command tiếp theo qua Outbox.
 * <p>
 * Mọi method bắt buộc chạy trong transaction có sẵn của caller (Propagation.MANDATORY): state Saga, đơn hàng
 * và command kế tiếp được commit CÙNG NHAU hoặc không gì cả.
 */
public interface CheckoutSagaOrchestrator {

    /** Gọi bởi CheckoutService, trong transaction tạo đơn. Phát command ReserveInventory. */
    CheckoutSaga start(Order order, String correlationId);

    /** Gọi bởi SagaReplyListener khi có reply từ Inventory/Payment. Reply trễ/trùng bị bỏ qua an toàn. */
    void onReply(EventEnvelope envelope, SagaReplyEvent reply);

    /** Gọi bởi SagaTimeoutWatcher cho Saga đã quá deadline (đã bị khóa bằng FOR UPDATE SKIP LOCKED). */
    void onTimeout(CheckoutSaga saga);
}
