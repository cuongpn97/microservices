package com.webshop.order.enums;

/** Command mà Saga cần phát ra sau một lần chuyển trạng thái (orchestrator sẽ ghi chúng vào Outbox). */
public enum SagaCommand {
    RESERVE_INVENTORY,
    RESERVE_CREDIT,
    COMMIT_INVENTORY,
    CAPTURE_CREDIT,
    RELEASE_CREDIT,
    RELEASE_INVENTORY
}
