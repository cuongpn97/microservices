package com.webshop.product.controller;

import com.webshop.product.dto.request.ImageOrderRequest;
import com.webshop.product.dto.request.ImageUpdateRequest;
import com.webshop.product.dto.response.ProductDetailResponse;
import com.webshop.product.service.ProductImageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/** Ảnh của một SPU (chỉ ADMIN). Upload là multipart/form-data: part "file" + field "altText" (tùy chọn). */
@RestController
@RequestMapping("/api/products/manage/{productId}/images")
@PreAuthorize("hasRole('ADMIN')")
@Validated
@RequiredArgsConstructor
public class ProductImageController {

    private final ProductImageService productImageService;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ProductDetailResponse upload(@PathVariable UUID productId, @RequestPart("file") MultipartFile file,
                                       @RequestParam(required = false) @Size(max = 200) String altText) {
        return productImageService.upload(productId, file, altText);
    }

    /** Ảnh đầu tiên trong danh sách là ảnh đại diện. */
    @PutMapping("/order")
    public ProductDetailResponse reorder(@PathVariable UUID productId, @Valid @RequestBody ImageOrderRequest request) {
        return productImageService.reorder(productId, request.imageIds());
    }

    @PutMapping("/{imageId}")
    public ProductDetailResponse update(@PathVariable UUID productId, @PathVariable UUID imageId,
                                        @Valid @RequestBody ImageUpdateRequest request) {
        return productImageService.changeAltText(productId, imageId, request.altText());
    }

    @DeleteMapping("/{imageId}")
    public ProductDetailResponse delete(@PathVariable UUID productId, @PathVariable UUID imageId) {
        return productImageService.delete(productId, imageId);
    }
}
