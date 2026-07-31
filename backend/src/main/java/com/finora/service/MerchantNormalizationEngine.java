package com.finora.service;

import com.finora.entity.Merchant;
import com.finora.entity.MerchantAlias;
import com.finora.repository.MerchantAliasRepository;
import com.finora.repository.MerchantRepository;
import com.finora.util.CategoryRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Resolves a raw transaction description to a single canonical Merchant, grouping variants
 * like "AMAZON SELLER SERVICES", "Amazon Pay", and "Amazon Marketplace" under one identity.
 *
 * Exact alias matching (same normalized description seen before) always wins first. For a
 * genuinely new description, grouping is done by the FIRST significant token of the normalized
 * text ("amazon" from all three examples above) — a deliberately simple heuristic, not fuzzy
 * matching or NLP. It correctly groups the common case (a brand name as the first word) and
 * will miss less obvious cases (e.g. a payment processor's legal name that doesn't share a
 * token with the brand it processes for) — those are exactly what the manual "merge merchants"
 * feature exists to fix by hand, rather than trying to make the heuristic itself smarter.
 */
@Service
public class MerchantNormalizationEngine {

    private final MerchantRepository merchantRepository;
    private final MerchantAliasRepository merchantAliasRepository;

    public MerchantNormalizationEngine(MerchantRepository merchantRepository, MerchantAliasRepository merchantAliasRepository) {
        this.merchantRepository = merchantRepository;
        this.merchantAliasRepository = merchantAliasRepository;
    }

    @Transactional
    public Merchant resolve(UUID userId, String description) {
        String normalizedAlias = CategoryRules.normalize(description);

        var existingAlias = merchantAliasRepository.findByUserIdAndNormalizedAlias(userId, normalizedAlias);
        if (existingAlias.isPresent()) {
            return merchantRepository.findById(existingAlias.get().getMerchantId())
                    .orElseGet(() -> createMerchantAndAlias(userId, description, normalizedAlias));
        }

        String firstToken = firstSignificantToken(normalizedAlias);
        if (firstToken != null) {
            var candidate = merchantRepository.findByUserId(userId).stream()
                    .filter(m -> firstToken.equals(firstSignificantToken(CategoryRules.normalize(m.getCanonicalName()))))
                    .findFirst();
            if (candidate.isPresent()) {
                addAlias(candidate.get().getId(), userId, normalizedAlias);
                return candidate.get();
            }
        }

        return createMerchantAndAlias(userId, description, normalizedAlias);
    }

    private Merchant createMerchantAndAlias(UUID userId, String description, String normalizedAlias) {
        Merchant merchant = new Merchant();
        merchant.setUserId(userId);
        merchant.setCanonicalName(toDisplayName(CategoryRules.extractMerchant(description)));
        merchant = merchantRepository.save(merchant);
        addAlias(merchant.getId(), userId, normalizedAlias);
        return merchant;
    }

    private void addAlias(UUID merchantId, UUID userId, String normalizedAlias) {
        if (merchantAliasRepository.findByUserIdAndNormalizedAlias(userId, normalizedAlias).isPresent()) return;
        MerchantAlias alias = new MerchantAlias();
        alias.setMerchantId(merchantId);
        alias.setUserId(userId);
        alias.setNormalizedAlias(normalizedAlias);
        merchantAliasRepository.save(alias);
    }

    private String firstSignificantToken(String normalized) {
        if (normalized == null || normalized.isBlank()) return null;
        String[] tokens = normalized.split(" ");
        for (String t : tokens) {
            if (t.length() > 2) return t;
        }
        return tokens.length > 0 ? tokens[0] : null;
    }

    private String toDisplayName(String extractedMerchant) {
        if (extractedMerchant == null || extractedMerchant.isBlank()) return "Unknown Merchant";
        String[] words = extractedMerchant.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
}
