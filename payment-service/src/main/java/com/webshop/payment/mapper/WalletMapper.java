package com.webshop.payment.mapper;

import com.webshop.payment.dto.response.CreditReservationResponse;
import com.webshop.payment.dto.response.LedgerEntryResponse;
import com.webshop.payment.dto.response.WalletResponse;
import com.webshop.payment.entity.CreditReservation;
import com.webshop.payment.entity.LedgerEntry;
import com.webshop.payment.entity.WalletSummary;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

import java.util.List;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface WalletMapper {

    /** availableVnd() là method tính toán của record, không phải component → khai báo bằng expression. */
    @Mapping(target = "availableVnd", expression = "java(wallet.availableVnd())")
    WalletResponse toWalletResponse(WalletSummary wallet);

    /** enum type → String được MapStruct tự đổi bằng name(). */
    LedgerEntryResponse toLedgerEntryResponse(LedgerEntry entry);

    List<LedgerEntryResponse> toLedgerEntryResponses(List<LedgerEntry> entries);

    @Mapping(target = "reservationId", source = "id")
    CreditReservationResponse toCreditReservationResponse(CreditReservation reservation);
}
