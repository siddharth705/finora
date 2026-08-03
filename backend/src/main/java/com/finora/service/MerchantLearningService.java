package com.finora.service;

import com.finora.dto.LearningDto.Summary;
import com.finora.dto.LearningDto.TimelineEntry;
import com.finora.entity.Category;
import com.finora.entity.Merchant;
import com.finora.entity.MerchantCategoryLearning;
import com.finora.entity.MerchantLearningAudit;
import com.finora.exception.ApiException;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.repository.MerchantLearningAuditRepository;
import com.finora.repository.MerchantRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The Learning Engine (spec Section 2, 7.2, 9.1, 11 Milestone A). Records user confirmations
 * against a merchant's category distribution, recomputes confidence from real evidence, writes
 * an append-only audit trail, and supports undo.
 *
 * Deliberately NOT wired into TransactionService/ImportService yet - per the spec's Milestone A
 * scope, this is built and tested standalone first. When Milestone B integrates it, confirm()
 * will need to run with Propagation.REQUIRES_NEW so a learning-update failure doesn't roll back
 * the caller's already-applied category change (spec Section 10: "Learning update fails after a
 * transaction has been categorized" - do NOT rollback the category, only retry/flag learning).
 * That propagation change is out of scope for this milestone and left as a one-line TODO rather
 * than solved speculatively now.
 *
 * Financial Intelligence Workspace, Learning Engine module additions: reset() (bulk-clear a
 * merchant's distribution, distinct from undo()'s one-step-back), timeline()/summary() (the
 * cross-merchant views the Workspace's own Learning Engine page needs -- MerchantDto.AuditEntry
 * is scoped to one already-known merchant, which isn't enough here). merchantRepository/
 * categoryRepository are new dependencies purely for name resolution in those two read methods;
 * confirm()/undo()/reset() don't touch either. "Disable Learning" (a toggle stopping future
 * learning writes) was considered alongside these and deliberately NOT built here -- unlike
 * reset() (a bulk variant of the undo already exposed), it would change CategorizationService's
 * write-path behavior itself, which is a System Settings-shaped decision the kickoff memo's
 * Workspace scope didn't resolve one way or the other; left for that module or a follow-up.
 */
@Service
public class MerchantLearningService {

    private final MerchantCategoryLearningRepository learningRepository;
    private final MerchantLearningAuditRepository auditRepository;
    private final MerchantRepository merchantRepository;
    private final CategoryRepository categoryRepository;
    private final ConfidenceEngine confidenceEngine;
    private final AuditService auditService;

    public MerchantLearningService(MerchantCategoryLearningRepository learningRepository,
                                    MerchantLearningAuditRepository auditRepository,
                                    MerchantRepository merchantRepository,
                                    CategoryRepository categoryRepository,
                                    ConfidenceEngine confidenceEngine,
                                    AuditService auditService) {
        this.learningRepository = learningRepository;
        this.auditRepository = auditRepository;
        this.merchantRepository = merchantRepository;
        this.categoryRepository = categoryRepository;
        this.confidenceEngine = confidenceEngine;
        this.auditService = auditService;
    }

    public record LearningResult(List<MerchantCategoryLearning> distribution, MerchantLearningAudit auditEntry) {}

    /**
     * Records a user's confirmation that categoryId is correct for merchantId. Increments the
     * existing (merchant, category) pair if one exists, or creates a new one at count=1.
     * Recomputes confidence for the merchant's ENTIRE distribution (not just the confirmed pair) -
     * confirming one category shifts every other category's share too, since they're all
     * fractions of the same total.
     *
     * Audited as LEARNED if this is the first-ever confirmation for this merchant, or if the
     * confirmed category was already the top pick going in. Audited as CORRECTED if a different
     * category was previously the top pick - this IS the conflict-detection the spec calls for,
     * expressed as "did the leading category change" rather than a separate conflict flag.
     */
    @Transactional
    public LearningResult confirm(UUID userId, UUID merchantId, UUID categoryId) {
        List<MerchantCategoryLearning> existingPairs = learningRepository.findByUserIdAndMerchantId(userId, merchantId);
        MerchantCategoryLearning previousTop = confidenceEngine.topCategory(existingPairs);
        UUID previousTopCategoryId = previousTop != null ? previousTop.getCategoryId() : null;

        MerchantCategoryLearning target = existingPairs.stream()
                .filter(p -> p.getCategoryId().equals(categoryId))
                .findFirst()
                .orElseGet(() -> {
                    MerchantCategoryLearning fresh = new MerchantCategoryLearning();
                    fresh.setUserId(userId);
                    fresh.setMerchantId(merchantId);
                    fresh.setCategoryId(categoryId);
                    fresh.setConfirmationCount(0); // incremented below, uniformly with the existing-pair path
                    existingPairs.add(fresh);
                    return fresh;
                });

        target.setConfirmationCount(target.getConfirmationCount() + 1);
        target.setLastConfirmedAt(Instant.now());
        target.setUpdatedAt(Instant.now());

        recomputeAndSave(existingPairs);

        boolean differsFromPreviousTop = previousTopCategoryId == null || !previousTopCategoryId.equals(categoryId);
        MerchantLearningAudit.Action action = differsFromPreviousTop && previousTopCategoryId != null
                ? MerchantLearningAudit.Action.CORRECTED
                : MerchantLearningAudit.Action.LEARNED;

        MerchantLearningAudit audit = new MerchantLearningAudit();
        audit.setMerchantId(merchantId);
        audit.setUserId(userId);
        audit.setAction(action);
        audit.setPreviousCategoryId(previousTopCategoryId);
        audit.setNewCategoryId(categoryId);
        auditRepository.save(audit);

        return new LearningResult(learningRepository.findByUserIdAndMerchantId(userId, merchantId), audit);
    }

    /**
     * Reverts the merchant's most recent audit entry. Only LEARNED/CORRECTED entries represent
     * something with a real confirmation-count to revert; if the most recent entry is itself an
     * UNDONE (or MERGED) entry, there's nothing well-defined to do (redo isn't in scope for this
     * milestone), so this throws rather than guessing.
     */
    @Transactional
    public LearningResult undo(UUID userId, UUID merchantId) {
        List<MerchantLearningAudit> history = auditRepository.findByUserIdAndMerchantIdOrderByCreatedAtDesc(userId, merchantId);
        MerchantLearningAudit mostRecent = history.stream().findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "No learning history to undo for this merchant."));

        // Bug fix: this used to only reject UNDONE/MERGED, but a RESET entry has the exact same
        // "nothing well-defined to revert" property -- worse, actually, since reset() deletes the
        // merchant's ENTIRE distribution unconditionally (not one pair), and always writes
        // newCategoryId=null (see reset()). Before this fix, calling undo() right after a reset()
        // slipped past this guard, then looked up a pair matching categoryId=null (never found
        // one), silently changed nothing, and still wrote a fresh UNDONE audit entry -- misleading
        // the user into thinking their reset had been reverted when nothing was.
        if (mostRecent.getAction() == MerchantLearningAudit.Action.UNDONE
                || mostRecent.getAction() == MerchantLearningAudit.Action.MERGED
                || mostRecent.getAction() == MerchantLearningAudit.Action.RESET) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "The most recent action for this merchant can't be undone (it was itself an undo, a merge, or a reset).");
        }

        UUID categoryToRevert = mostRecent.getNewCategoryId();
        List<MerchantCategoryLearning> pairs = learningRepository.findByUserIdAndMerchantId(userId, merchantId);

        Optional<MerchantCategoryLearning> target = pairs.stream()
                .filter(p -> p.getCategoryId().equals(categoryToRevert))
                .findFirst();

        if (target.isPresent()) {
            MerchantCategoryLearning pair = target.get();
            int newCount = pair.getConfirmationCount() - 1;
            if (newCount <= 0) {
                pairs.remove(pair);
                learningRepository.delete(pair);
            } else {
                pair.setConfirmationCount(newCount);
                pair.setUpdatedAt(Instant.now());
            }
        }

        recomputeAndSave(pairs);

        MerchantLearningAudit undoEntry = new MerchantLearningAudit();
        undoEntry.setMerchantId(merchantId);
        undoEntry.setUserId(userId);
        undoEntry.setAction(MerchantLearningAudit.Action.UNDONE);
        undoEntry.setPreviousCategoryId(categoryToRevert);
        undoEntry.setNewCategoryId(null);
        auditRepository.save(undoEntry);

        // Bug fix: undo()/reset() are the only two mutating actions in this class that never
        // called AuditService, unlike every other mutating service in the codebase (and unlike
        // MerchantService.rename()/merge(), which had this exact gap fixed for the same reason --
        // see that class's own doc comment). Both are now reachable ONLY via
        // AdminUserMerchantController (self-service MerchantController was retired), making this
        // an admin action against another user's data with previously zero trace in the general
        // activity feed -- only merchant_learning_audit (scoped to that one merchant) recorded it.
        auditService.record(userId, "MERCHANT_LEARNING_UNDONE", "Merchant", merchantId);

        return new LearningResult(learningRepository.findByUserIdAndMerchantId(userId, merchantId), undoEntry);
    }

    public List<MerchantCategoryLearning> distributionFor(UUID userId, UUID merchantId) {
        return learningRepository.findByUserIdAndMerchantId(userId, merchantId);
    }

    /**
     * Financial Intelligence Workspace, Learning Engine module -- wipes this merchant's ENTIRE
     * distribution unconditionally, unlike undo() which only reverts the single most recent
     * confirmation and requires an audit-history chain to walk back through. Future transactions
     * for this merchant simply get no learned-category signal until the user confirms again from
     * scratch (CategorizationService.suggest() reads the distribution live -- see its own doc
     * comment -- so nothing else needs to change for that to take effect). Already-categorized
     * past transactions are untouched; this only clears what future suggestions would draw on.
     */
    @Transactional
    public MerchantLearningAudit reset(UUID userId, UUID merchantId) {
        List<MerchantCategoryLearning> pairs = learningRepository.findByUserIdAndMerchantId(userId, merchantId);
        if (pairs.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "This merchant has no learning history to reset.");
        }
        MerchantCategoryLearning previousTop = confidenceEngine.topCategory(pairs);
        UUID previousTopCategoryId = previousTop != null ? previousTop.getCategoryId() : null;

        learningRepository.deleteAll(pairs);

        MerchantLearningAudit resetEntry = new MerchantLearningAudit();
        resetEntry.setMerchantId(merchantId);
        resetEntry.setUserId(userId);
        resetEntry.setAction(MerchantLearningAudit.Action.RESET);
        resetEntry.setPreviousCategoryId(previousTopCategoryId);
        resetEntry.setNewCategoryId(null);
        MerchantLearningAudit saved = auditRepository.save(resetEntry);

        // See undo()'s own comment above -- same gap, same fix.
        auditService.record(userId, "MERCHANT_LEARNING_RESET", "Merchant", merchantId);

        return saved;
    }

    /** Financial Intelligence Workspace, Learning Engine module -- every learning event across
     *  every merchant, newest first. See MerchantLearningAuditRepository.findByUserId's own doc
     *  comment for why a plain userId-scoped query is safe here. */
    @Transactional(readOnly = true)
    public List<TimelineEntry> timeline(UUID userId) {
        Map<UUID, String> merchantNames = new HashMap<>();
        for (Merchant m : merchantRepository.findByUserId(userId)) merchantNames.put(m.getId(), m.getCanonicalName());
        Map<UUID, String> categoryNames = new HashMap<>();
        for (Category c : categoryRepository.findByUserId(userId)) categoryNames.put(c.getId(), c.getName());

        // Bug fix: .sorted(comparing(createdAt).reversed()) looked right but wasn't -- Java's
        // sort is stable, and reversing a comparator doesn't change what counts as "equal," so
        // two entries with the exact same createdAt (very reachable: back-to-back confirm() calls
        // easily land in the same Instant.now() tick, especially on Windows' coarser clock
        // resolution) kept their ORIGINAL retrieval order rather than reflecting which one was
        // actually created more recently. Sorting ascending first (stable; ties keep DB/insertion
        // order, i.e. oldest-among-ties first) and then reversing the whole list fixes this: ties
        // now come out most-recently-inserted-first, matching what "newest first" actually means.
        List<MerchantLearningAudit> chronological = new ArrayList<>(auditRepository.findByUserId(userId));
        chronological.sort(Comparator.comparing(MerchantLearningAudit::getCreatedAt));
        Collections.reverse(chronological);

        return chronological.stream()
                .map(a -> new TimelineEntry(
                        a.getId(), a.getMerchantId(), merchantNames.getOrDefault(a.getMerchantId(), "Unknown merchant"),
                        a.getAction().name(),
                        a.getPreviousCategoryId() != null ? categoryNames.get(a.getPreviousCategoryId()) : null,
                        a.getNewCategoryId() != null ? categoryNames.get(a.getNewCategoryId()) : null,
                        a.getCreatedAt()))
                .toList();
    }

    /** Financial Intelligence Workspace, Learning Engine module -- aggregate stats for the page
     *  header. learnedMerchants counts distinct merchants with at least one MerchantCategoryLearning
     *  row today (current state), while totalConfirmations/correctedCount/resetCount are lifetime
     *  counts off the audit trail (history, including merchants since reset back to zero) -- these
     *  two views can legitimately disagree, e.g. right after a reset the audit trail still shows
     *  that merchant's past LEARNED/CORRECTED entries even though it no longer counts toward
     *  learnedMerchants. */
    @Transactional(readOnly = true)
    public Summary summary(UUID userId) {
        Set<UUID> learnedMerchantIds = new HashSet<>();
        for (MerchantCategoryLearning pair : learningRepository.findByUserId(userId)) {
            learnedMerchantIds.add(pair.getMerchantId());
        }

        List<MerchantLearningAudit> history = auditRepository.findByUserId(userId);
        long correctedCount = history.stream().filter(a -> a.getAction() == MerchantLearningAudit.Action.CORRECTED).count();
        long resetCount = history.stream().filter(a -> a.getAction() == MerchantLearningAudit.Action.RESET).count();
        long totalConfirmations = history.stream()
                .filter(a -> a.getAction() == MerchantLearningAudit.Action.LEARNED
                        || a.getAction() == MerchantLearningAudit.Action.CORRECTED)
                .count();

        return new Summary(learnedMerchantIds.size(), totalConfirmations, correctedCount, resetCount);
    }

    private void recomputeAndSave(List<MerchantCategoryLearning> pairs) {
        var confidenceByCategory = confidenceEngine.recomputeDistribution(pairs);
        for (MerchantCategoryLearning pair : pairs) {
            pair.setConfidence(confidenceByCategory.getOrDefault(pair.getCategoryId(), 0));
        }
        learningRepository.saveAll(pairs);
    }
}
