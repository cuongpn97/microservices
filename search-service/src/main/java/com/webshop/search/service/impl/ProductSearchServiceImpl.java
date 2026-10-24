package com.webshop.search.service.impl;

import com.webshop.search.dto.request.SearchQuery;
import com.webshop.search.dto.response.ProductSearchResponse;
import com.webshop.search.mapper.ProductSearchMapper;
import com.webshop.search.repository.ProductSearchRepository;
import com.webshop.search.service.ProductSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ProductSearchServiceImpl implements ProductSearchService {

    private final ProductSearchRepository productSearchRepository;
    private final ProductSearchMapper productSearchMapper;

    @Override
    public ProductSearchResponse search(SearchQuery query) {
        return productSearchMapper.toResponse(productSearchRepository.search(query), query);
    }
}
