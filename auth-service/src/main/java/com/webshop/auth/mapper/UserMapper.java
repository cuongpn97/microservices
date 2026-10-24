package com.webshop.auth.mapper;

import com.webshop.auth.dto.response.UserResponse;
import com.webshop.auth.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface UserMapper {

    /** passwordHash không có trong UserResponse nên MapStruct tự bỏ qua. */
    UserResponse toResponse(User user);
}
