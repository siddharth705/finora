package com.finora.service;

import com.finora.dto.MerchantDto;
import com.finora.transactions.TransactionDto;
import com.finora.entity.Category;
import com.finora.entity.Merchant;
import com.finora.entity.MerchantAlias;
import com.finora.entity.MerchantCategoryLearning;
import com.finora.entity.MerchantLearningAudit;
import com.finora.entity.Transaction;
import com.finora.exception.ApiException;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantAliasRepository;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.repository.MerchantLearningAuditRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.TransactionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Merchant Management API backing service -- list/detail/audit-history/rename/merge, per
 * docs/financial-intelligence-engine-spec.md §5.1-5.4.
 *
 * confirm-category (§5.5) and undo (§5.6) are NOT implemented here -- they're
 * MerchantController endpoints backed directly by TransactionService.confirmMerchantCategory()
 * and MerchantLearningService.undo() respectively, not by this class. See MerchantController's
 * class comment.
 *
 * Bug fix (Workspace Activity Timeline, see docs/team-message-financial-intelligence-workspace-
 * kickoff.md module 9): this class previously never called AuditService at all, unlike every
 * other mutating service in the codebase (AccountService, RuleService, RelationshipService,
 * StatementImportService, TransactionService). A merchant rename was invisible in every audit
 * trail; a merge was captured only in merchant_learning_audit (as MERGED, scoped to that one
 * merchant's own history), never in the general activity feed ActivityController now exposes.
 */
@Service
public class MerchantService {

    private final MerchantRepository merchantRepository;
    private final MerchantAliasRepository merchantAliasRepository;
    private final MerchantCategoryLearningRepository learningRepository;
    private final MerchantLearningAuditRepository auditRepository;
    private final CategoryRepository categoryRepository;
    private final TransactionRepository transactionRepository;
    private final ConfidenceEngine confidenceEngine;
    private final AuditService auditService;

    public MerchantService(MerchantRepository merchantRepository,
                            MerchantAliasRepository merchantAliasRepository,
                            MerchantCategoryLearningRepository learningRepository,
                            MerchantLearningAuditRepository auditRepository,
                            CategoryRepository categoryRepository,
                            TransactionRepository transactionRepository,
                            ConfidenceEngine confidenceEngine,
                            AuditService auditService) {
        this.merchantRepository = merchantRepository;
        this.merchantAliasRepository = merchantAliasRepository;
        this.learningRepository = learningRepository;
        this.auditRepository = auditRepository;
        this.categoryRepository = categoryRepository;
        this.transactionRepository = transactionRepository;
        this.confidenceEngine = confidenceEngine;
        this.auditService = auditService;
    }

    // Bug fix: this used to call toDto(userId, m, categoryNames) per merchant, and toDto() itself
    // ran learningRepository.findByUserIdAndMerchantId() -- one query per merchant on top of the
    // one query for the merchant list itself. A user with a multi-year import history routinely
    // has 100-500+ merchants (MerchantNormalizationEngine creates one per distinct normalized
    // payee ever seen), so this endpoint issued 100-500+ sequential queries on every single
    // request. Same fix AnalyticsService.categoryConfidence() already applies: one bulk
    // learningRepository.findByUserId() call, grouped in memory by merchantId.
    public List<MerchantDto> listForUser(UUID userId) {
        Map<UUID, String> categoryNames = categoryNamesFor(userId);
        Map<UUID, List<MerchantCategoryLearning>> pairsByMerchant = learningRepository.findByUserId(userId).stream()
                .collect(Collectors.groupingBy(MerchantCategoryLearning::getMerchantId));
        return merchantRepository.findByUserId(userId).stream()
                .map(m -> toDto(m, pairsByMerchant.getOrDefault(m.getId(), List.of()), categoryNames))
                .toList();
    }

    public MerchantDto get(UUID userId, UUID merchantId) {
        Merchant merchant = requireOwnedMerchant(userId, merchantId);
        return toDto(merchant, learningRepository.findByUserIdAndMerchantId(userId, merchant.getId()), categoryNamesFor(userId));
    }

    /** Financial Intelligence Workspace, Module 2 (Merchant Management) -- the one endpoint that
     *  genuinely didn't exist yet; everything else the console needs (search, confidence,
     *  learning stats, rename, merge, audit, category distribution) was already here. Reuses
     *  TransactionRepository.findByUserIdAndMerchantId(), the exact same query
     *  MerchantService.merge() already uses to find what needs repointing -- no new query shape,
     *  just a new read-only caller of an existing one. */
    public List<TransactionDto> transactionsFor(UUID userId, UUID merchantId) {
        requireOwnedMerchant(userId, merchantId); // ownership check, even though the query below is itself user-scoped
        Map<UUID, String> categoryNames = categoryNamesFor(userId);
        return transactionRepository.findByUserIdAndMerchantId(userId, merchantId).stream()
                .sorted(Comparator.comparing(Transaction::getTxnDate).reversed())
                .map(t -> TransactionDto.from(t, categoryNames.getOrDefault(t.getCategoryId(), "Uncategorized")))
                .toList();
    }

    public List<MerchantDto.AuditEntry> auditHistory(UUID userId, UUID merchantId) {
        requireOwnedMerchant(userId, merchantId); // ownership check, even though the query below is itself user-scoped
        Map<UUID, String> categoryNames = categoryNamesFor(userId);
        return auditRepository.findByUserIdAndMerchantIdOrderByCreatedAtDesc(userId, merchantId).stream()
                .map(a -> new MerchantDto.AuditEntry(
                        a.getAction().name(),
                        a.getPreviousCategoryId() != null ? categoryNames.get(a.getPreviousCategoryId()) : null,
                        a.getNewCategoryId() != null ? categoryNames.get(a.getNewCategoryId()) : null,
                        a.getCreatedAt()))
                .toList();
    }

    @Transactional
    public MerchantDto rename(UUID userId, UUID merchantId, MerchantDto.UpdateRequest request) {
        Merchant merchant = requireOwnedMerchant(userId, merchantId);
        String previousName = merchant.getCanonicalName();
        if (request.canonicalName() != null) {
            if (request.canonicalName().isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "canonicalName can't be blank.");
            }
            merchant.setCanonicalName(request.canonicalName());
        }
        if (request.website() != null) {
            merchant.setWebsite(request.website());
        }
        merchant.setUpdatedAt(Instant.now());
        merchantRepository.save(merchant);
        auditService.record(userId, "MERCHANT_UPDATED", "Merchant", merchant.getId(),
                Map.of("previousName", previousName, "newName", merchant.getCanonicalName()));
        return toDto(merchant, learningRepository.findByUserIdAndMerchantId(userId, merchant.getId()), categoryNamesFor(userId));
    }

    /**
     * Merge, per spec §5.4's numbered steps (referenced by number in the comments below). Runs
     * inside one @Transactional boundary on purpose -- §10.4 of the spec calls a half-merged
     * merchant (some rows repointed, some not, absorbed row still present or already gone) a
     * worse state than the merge simply not having happened, so this must be all-or-nothing.
     *
     * One addition beyond the spec's literal 7 steps: the absorbed merchant's OWN pre-merge audit
     * history (LEARNED/CORRECTED rows from before the merge) is repointed onto the surviving
     * merchant rather than left to cascade-delete with the absorbed row. merchant_learning_audit
     * has ON DELETE CASCADE on merchant_id (V7 migration) -- letting that fire would silently
     * erase real history for an audit trail the spec elsewhere describes as append-only and the
     * whole reason "why is this categorized this way" is answerable. Silently losing it on merge
     * would contradict that design, so it's preserved.
     */
    @Transactional
    public MerchantDto merge(UUID userId, UUID survivingMerchantId, UUID mergeFromMerchantId) {
        if (survivingMerchantId.equals(mergeFromMerchantId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Can't merge a merchant into itself.");
        }
        Merchant surviving = requireOwnedMerchant(userId, survivingMerchantId);
        Merchant absorbed = requireOwnedMerchant(userId, mergeFromMerchantId);

        // 1. Repoint aliases belonging to the absorbed merchant onto the surviving one.
        List<MerchantAlias> aliases = merchantAliasRepository.findByMerchantId(absorbed.getId());
        for (MerchantAlias alias : aliases) {
            alias.setMerchantId(surviving.getId());
        }
        merchantAliasRepository.saveAll(aliases);

        // 2. Repoint every transaction currently resolved to the absorbed merchant.
        List<Transaction> transactions = transactionRepository.findByUserIdAndMerchantId(userId, absorbed.getId());
        for (Transaction t : transactions) {
            t.setMerchantId(surviving.getId());
        }
        transactionRepository.saveAll(transactions);

        // 3. Sum distribution rows for the same category on both merchants (not replace).
        List<MerchantCategoryLearning> survivingPairs = learningRepository.findByUserIdAndMerchantId(userId, surviving.getId());
        List<MerchantCategoryLearning> absorbedPairs = learningRepository.findByUserIdAndMerchantId(userId, absorbed.getId());

        Map<UUID, MerchantCategoryLearning> byCategory = new HashMap<>();
        for (MerchantCategoryLearning pair : survivingPairs) {
            byCategory.put(pair.getCategoryId(), pair);
        }
        List<MerchantCategoryLearning> toDelete = new ArrayList<>();
        for (MerchantCategoryLearning absorbedPair : absorbedPairs) {
            MerchantCategoryLearning existing = byCategory.get(absorbedPair.getCategoryId());
            if (existing != null) {
                existing.setConfirmationCount(existing.getConfirmationCount() + absorbedPair.getConfirmationCount());
                if (absorbedPair.getLastConfirmedAt().isAfter(existing.getLastConfirmedAt())) {
                    existing.setLastConfirmedAt(absorbedPair.getLastConfirmedAt());
                }
                toDelete.add(absorbedPair); // summed into `existing`, this row is now redundant
            } else {
                // No conflicting category on the surviving merchant -- repoint the whole row
                // rather than copy it, preserving its original confirmation_count as-is.
                absorbedPair.setMerchantId(surviving.getId());
                byCategory.put(absorbedPair.getCategoryId(), absorbedPair);
            }
        }

        // 4. Recompute confidence for the merged distribution via the same function used
        // everywhere else -- not special-cased merge math.
        List<MerchantCategoryLearning> finalPairs = new ArrayList<>(byCategory.values());
        Map<UUID, Integer> confidenceByCategory = confidenceEngine.recomputeDistribution(finalPairs);
        for (MerchantCategoryLearning pair : finalPairs) {
            pair.setConfidence(confidenceByCategory.getOrDefault(pair.getCategoryId(), 0));
            pair.setUpdatedAt(Instant.now());
        }
        learningRepository.saveAll(finalPairs);
        learningRepository.deleteAll(toDelete);

        // Preserve the absorbed merchant's pre-merge audit history -- see class-level doc comment.
        List<MerchantLearningAudit> absorbedAudit = auditRepository.findByUserIdAndMerchantIdOrderByCreatedAtDesc(userId, absorbed.getId());
        for (MerchantLearningAudit entry : absorbedAudit) {
            entry.setMerchantId(surviving.getId());
        }
        auditRepository.saveAll(absorbedAudit);

        // 6. A single MERGED audit entry on the surviving merchant.
        MerchantLearningAudit mergedEntry = new MerchantLearningAudit();
        mergedEntry.setMerchantId(surviving.getId());
        mergedEntry.setUserId(userId);
        mergedEntry.setAction(MerchantLearningAudit.Action.MERGED);
        auditRepository.save(mergedEntry);

        // 7. Delete the absorbed merchant row only after every repoint above has succeeded.
        merchantRepository.delete(absorbed);

        // General activity feed entry (ActivityController) -- distinct from the MERGED row
        // written to merchant_learning_audit above, which is scoped to the surviving merchant's
        // own audit history, not the cross-entity feed a Workspace user reviewing "what changed
        // recently" would look at.
        auditService.record(userId, "MERCHANT_MERGED", "Merchant", surviving.getId(),
                Map.of("survivingMerchantId", surviving.getId(), "mergeFromMerchantId", absorbed.getId(),
                        "mergeFromName", absorbed.getCanonicalName()));

        // 5. Return the freshly recomputed distribution, not a stale pre-merge snapshot -- reuses
        // finalPairs (already computed above in step 4) instead of re-querying for what's already
        // sitting in memory.
        return toDto(surviving, finalPairs, categoryNamesFor(userId));
    }

    private Merchant requireOwnedMerchant(UUID userId, UUID merchantId) {
        return merchantRepository.findByIdAndUserId(merchantId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Merchant not found."));
    }

    private Map<UUID, String> categoryNamesFor(UUID userId) {
        Map<UUID, String> names = new HashMap<>();
        for (Category c : categoryRepository.findByUserId(userId)) {
            names.put(c.getId(), c.getName());
        }
        return names;
    }

    private MerchantDto toDto(Merchant merchant, List<MerchantCategoryLearning> pairs, Map<UUID, String> categoryNames) {
        List<MerchantDto.DistributionEntry> distribution = pairs.stream()
                .sorted(Comparator.comparingInt(MerchantCategoryLearning::getConfirmationCount).reversed())
                .map(p -> new MerchantDto.DistributionEntry(
                        categoryNames.getOrDefault(p.getCategoryId(), "Unknown"),
                        p.getConfirmationCount(),
                        p.getConfidence()))
                .toList();

        MerchantCategoryLearning top = confidenceEngine.topCategory(pairs);
        String topCategory = top != null ? categoryNames.getOrDefault(top.getCategoryId(), "Unknown") : null;
        Integer topCategoryConfidence = top != null ? top.getConfidence() : null;

        return new MerchantDto(
                merchant.getId(), merchant.getCanonicalName(), merchant.getLogoUrl(), merchant.getWebsite(),
                topCategory, topCategoryConfidence, distribution
        );
    }
}
