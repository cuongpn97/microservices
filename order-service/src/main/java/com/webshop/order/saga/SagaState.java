package com.webshop.order.saga;

/**
 * Trạng thái Saga checkout và ý nghĩa nghiệp vụ CHÍNH XÁC:
 * <ul>
 *   <li>{@link #PENDING}: đơn + Saga + command ReserveInventory đã được ghi nguyên tử. Đang chờ Inventory trả lời.
 *       Chưa chắc đã giữ được hàng.</li>
 *   <li>{@link #INVENTORY_RESERVED}: Inventory xác nhận đã giữ hàng; command ReserveCredit đã phát.
 *       Đang chờ Payment trả lời.</li>
 *   <li>{@link #CONFIRMING}: đã giữ được CẢ hàng và credit → <b>điểm không quay đầu</b>. Đã phát CommitInventory
 *       và CaptureCredit. Từ đây Saga chỉ đi tiến (forward recovery): reservation không tự hết hạn nên commit/capture
 *       không thể thất bại vì lý do nghiệp vụ, chỉ cần retry tới khi có reply.</li>
 *   <li>{@link #COMPLETED}: tồn kho đã commit và credit đã capture. Đơn CONFIRMED. (terminal)</li>
 *   <li>{@link #COMPENSATING}: đã quyết định thất bại; đã phát lệnh release cho mọi tài nguyên CÓ THỂ đang bị giữ
 *       (thứ tự ngược: credit trước, hàng sau). Chờ đủ reply release.</li>
 *   <li>{@link #FAILED}: mọi tài nguyên đã được trả. Đơn FAILED. (terminal)</li>
 * </ul>
 */
public enum SagaState {
    PENDING,
    INVENTORY_RESERVED,
    CONFIRMING,
    COMPLETED,
    COMPENSATING,
    FAILED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
