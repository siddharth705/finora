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
 *
 * <h2>Known cost, measured and deliberately left alone</h2>
 *
 * Any description whose exact spelling has not been seen before falls through to
 * {@code merchantRepository.findByUserId(userId)} — the user's whole merchant table, loaded as
 * managed entities and filtered in Java. Measured in {@link MerchantNormalizationEngineTest}:
 *
 * <pre>
 *   500-row statement, 50 distinct merchants  ->  500 full merchant-table loads
 * </pre>
 *
 * One per ROW, not per merchant, because real bank descriptions carry a per-transaction reference
 * ("SWIGGY ORDER 4471"), so nearly every row is a new alias even when it is the same few merchants
 * repeatedly. Cost scales with rows × the user's lifetime merchant count, so it grows as an account
 * ages. A re-import of the same statement costs nothing extra — every alias is known by then.
 *
 * <p><b>Four fixes were considered. All were rejected; the reasoning is recorded so it does not
 * have to be rediscovered.</b>
 *
 * <ol>
 *   <li><b>Snapshot the merchant list once per import.</b> Two lines, and <i>wrong</i>.
 *       {@code resolve()} creates merchants as it goes, so row 3 would not see the merchant row 1
 *       created and would make its own. Three "Swiggy" rows instead of one, splitting the user's
 *       spend and splitting what the learning engine is taught — a silent data-quality bug, far
 *       worse than the latency it saves. Guarded by
 *       {@code differentSpellingsOfANewMerchantCollapseOntoOne}.</li>
 *
 *   <li><b>Transaction-scoped cache</b> (TransactionSynchronizationManager, or a context object
 *       threaded through {@code resolve()}). Correct, and would cut 500 scans to ~50. Rejected as
 *       disproportionate: it introduces caching machinery this codebase has nowhere else, and
 *       changes the signature every caller uses, to fix a sub-second cost.</li>
 *
 *   <li><b>Persist the normalised first token as an indexed column</b> and match in SQL. The
 *       principled fix, and the only one that removes the N+1 rather than shrinking it. Rejected
 *       for now because it is a schema migration plus a standing obligation: the token has to be
 *       recomputed wherever a canonical name changes, which today includes the admin
 *       rename-merchant and merge-merchant paths. Getting that wrong breaks matching silently.
 *       This is the one to revisit if import latency becomes a real complaint.</li>
 *
 *   <li><b>Read a two-column projection</b> instead of full entities, keeping the same Java
 *       matching. Built and measured, then reverted: it leaves the query count unchanged and
 *       <i>adds</i> a {@code findById} per token match — measured at <b>+450 lookups on a 500-row
 *       import</b>, taking it from 500 repository calls to 950. It trades round trips for
 *       hydration, and there was no measurement showing that trade comes out ahead.</li>
 * </ol>
 *
 * What was fixed, because it was an outright waste rather than a trade-off:
 * {@code ImportService} called {@code findByUserId(userId).size()} twice per import purely to
 * report how many merchants were newly learned, hydrating the entire table to produce a number the
 * database returns directly. That is now {@code countByUserId}.
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
