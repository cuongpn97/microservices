package com.webshop.product.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SpecificationRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank @Size(max = 500) String value) {
}
