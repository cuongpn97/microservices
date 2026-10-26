package com.webshop.product.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Một trục biến thể, ví dụ {"name":"Màu","values":["Đen","Trắng"]}. */
public record OptionRequest(
        @NotBlank @Size(max = 50) String name,
        @NotEmpty @Size(max = 30) List<@NotBlank @Size(max = 50) String> values) {
}
