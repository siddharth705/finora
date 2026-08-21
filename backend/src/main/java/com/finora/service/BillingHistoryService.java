package com.finora.service;

import com.finora.dto.BillingDtos.BillingHistoryEntryDto;
import com.finora.entity.Payment;
import com.finora.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * D-28 PR4-B. Read-only view over {@code payments} (proposal §3.4) -- no new backend concept
 * beyond the schema itself. Returns an empty list for every user today, correctly: no payment
 * gateway is wired up yet (§10), so no code path has ever inserted a row. This is the service a
 * future gateway integration's webhook handler populates into, not a placeholder to be rewritten.
 */
@Service
public class BillingHistoryService {

    private final PaymentRepository paymentRepository;

    public BillingHistoryService(PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
    }

    @Transactional(readOnly = true)
    public List<BillingHistoryEntryDto> history(UUID userId) {
        return paymentRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(p -> new BillingHistoryEntryDto(
                        p.getId(), p.getAmount(), p.getCurrency(), p.getProvider(), p.getStatus(), p.getCreatedAt()))
                .toList();
    }
}
