package com.webshop.product.dto.response;

import java.util.List;

public record OptionResponse(String name, List<String> values) {
}
