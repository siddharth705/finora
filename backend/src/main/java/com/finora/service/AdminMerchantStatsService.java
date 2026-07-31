package com.finora.service;

import com.finora.dto.AdminDtos.MerchantStatDto;
import com.finora.repository.MerchantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Platform-wide merchant catalog for the admin Merchant Intelligence page -- see
 * MerchantRepository.platformMerchantCounts()'s doc comment for exactly what's being aggregated
 * and why (there's no shared/canonical merchant table today, only every user's own private
 * Merchant rows). Deliberately just one query mapped into a DTO, the same "cheap live aggregate,
 * not a new reporting subsystem" shape as AdminStatsService.
 */
@Service
public class AdminMerchantStatsService {

    private final MerchantRepository merchantRepository;

    public AdminMerchantStatsService(MerchantRepository merchantRepository) {
        this.merchantRepository = merchantRepository;
    }

    @Transactional(readOnly = true)
    public List<MerchantStatDto> platformCatalog() {
        return merchantRepository.platformMerchantCounts().stream()
                .map(row -> new MerchantStatDto((String) row[0], (Long) row[1], (Long) row[2]))
                .toList();
    }
}
