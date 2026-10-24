package com.webshop.inventory.mapper;

import com.webshop.inventory.dto.response.ReservationLineResponse;
import com.webshop.inventory.dto.response.ReservationResponse;
import com.webshop.inventory.dto.response.StockResponse;
import com.webshop.inventory.entity.InventoryItem;
import com.webshop.inventory.entity.Reservation;
import com.webshop.inventory.entity.ReservationLine;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

import java.util.List;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface InventoryMapper {

    /** available() không phải getter (không có tiền tố get) nên MapStruct không tự thấy → khai báo bằng expression. */
    @Mapping(target = "available", expression = "java(item.available())")
    StockResponse toStockResponse(InventoryItem item);

    List<StockResponse> toStockResponses(List<InventoryItem> items);

    /** Tên lệch nhau (id → reservationId) nên cần @Mapping; enum status → String được MapStruct tự đổi bằng name(). */
    @Mapping(target = "reservationId", source = "id")
    ReservationResponse toReservationResponse(Reservation reservation);

    /** MapStruct dùng method này cho từng phần tử khi map Reservation.lines. */
    ReservationLineResponse toLineResponse(ReservationLine line);
}
