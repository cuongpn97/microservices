package com.webshop.order.service.impl;

import com.webshop.common.web.ApiException;
import com.webshop.order.dto.response.SagaResponse;
import com.webshop.order.entity.CheckoutSaga;
import com.webshop.order.mapper.OrderMapper;
import com.webshop.order.repository.CheckoutSagaRepository;
import com.webshop.order.repository.SagaTransitionRepository;
import com.webshop.order.service.SagaAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SagaAdminServiceImpl implements SagaAdminService {

    private final CheckoutSagaRepository checkoutSagaRepository;
    private final SagaTransitionRepository sagaTransitionRepository;
    private final OrderMapper orderMapper;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public SagaResponse getSaga(UUID sagaId) {
        CheckoutSaga saga = checkoutSagaRepository.findById(sagaId)
                .orElseThrow(() -> ApiException.notFound("SAGA_NOT_FOUND", "Saga not found"));
        return orderMapper.toSagaResponse(saga, sagaTransitionRepository.findBySagaId(sagaId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<SagaResponse> getSagasNeedingAttention() {
        return checkoutSagaRepository.findByNeedsAttentionTrueOrderByUpdatedAtDesc().stream()
                .map(s -> orderMapper.toSagaResponse(s, List.of())).toList();
    }

    @Override
    @Transactional
    public SagaResponse retryNow(UUID sagaId) {
        // Khóa dòng: không chạy xen với watcher/reply đang xử lý cùng Saga.
        CheckoutSaga saga = checkoutSagaRepository.lockById(sagaId)
                .orElseThrow(() -> ApiException.notFound("SAGA_NOT_FOUND", "Saga not found"));
        saga.expediteDeadline(clock.instant());
        return orderMapper.toSagaResponse(saga, List.of());
    }
}
