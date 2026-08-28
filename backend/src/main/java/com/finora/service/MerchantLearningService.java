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
 * This IS now wired into TransactionService/ImportService (the "deliberately NOT wired in yet"
 * note this comment used to carry expired at Milestone B). The isolation requirement it recorded
 * -- spec Section 10, "Learning update fails after a transaction has been categorized": do NOT
 * rollback the category, only retry/flag learning -- is still unmet. That is a separate, larger
 * question (learning failing should never roll back a categorization) from BH-053, which was
 * specifically the check-then-act race against V7's {@code UNIQUE(user_id, merchant_id,
 * category_id)} -- see confirm()'s own doc comment for how that one closed, and why the one-line
 * {@code Propagation.REQUIRES_NEW} this comment used to propose for it was never the fix.
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
    /**
     * <p><b>BH-053, closed.</b> The race was real: {@code filter(...).findFirst().orElseGet(...)}
     * used to be a check-then-act against V7's {@code UNIQUE(user_id, merchant_id, category_id)},
     * and because this joins the caller's transaction, a lost race took the caller down with it --
     * on {@code ImportService.confirm}, that was every transaction insert for a statement the user
     * had already reviewed.
     *
     * <p>{@code REQUIRES_NEW} was considered and rejected, and still would not work.
     * {@code merchant_category_learning} carries {@code NOT NULL} foreign keys to both
     * {@code merchants(id)} and {@code categories(id)}, and on the path that actually calls this
     * ({@code CategorizationService.learn} -> {@code MerchantNormalizationEngine.resolve} /
     * {@code resolveOrCreateCategory}) both parent rows are routinely created in the CALLER's
     * still-uncommitted transaction. A suspended-and-restarted inner transaction cannot see them,
     * so every first-time merchant or first-time category would have failed its foreign-key check.
     * That is why {@code StatementAnalysisRecorder} can use {@code REQUIRES_NEW} and this cannot:
     * its evidence row has no foreign key into the work being analysed.
     *
     * <p>Closed by removing the race rather than isolating it -- the rule
     * {@code MerchantNormalizationEngine.resolve} already states ("The write simply must not be
     * attempted"). {@link com.finora.repository.MerchantCategoryLearningRepository#ensurePairExists}
     * is a native, atomic upsert-or-noop, called here BEFORE any read, still inside this same
     * transaction. By the time this method reads {@code existingPairs} below, the target pair is
     * already guaranteed to exist -- {@code saveAll} in {@link #recomputeAndSave} can therefore
     * only ever issue UPDATEs against rows that are already there, never an INSERT that could
     * collide with a concurrent caller's.
     */
    @Transactional
    public LearningResult confirm(UUID userId, UUID merchantId, UUID categoryId) {
        learningRepository.ensurePairExists(userId, merchantId, categoryId);
        List<MerchantCategoryLearning> existingPairs = learningRepository.findByUserIdAndMerchantId(userId, merchantId);

        // ensurePairExists may itself be what created the target pair, at confirmationCount=0 --
        // that placeholder carries no real evidence and must not be eligible to win topCategory
        // (which is exactly what decides LEARNED vs CORRECTED below). Filtering it out here
        // reproduces the pre-fix ordering exactly: previousTop used to be computed before the new
        // pair was created at all, so it never saw it either. A pair that already had real
        // confirmations (count > 0) is unaffected either way -- ensurePairExists is a no-op
        // against it, so it is untouched here just as it was before this fix existed.
        MerchantCategoryLearning previousTop = confidenceEngine.topCategory(
                existingPairs.stream().filter(p -> p.getConfirmationCount() > 0).toList());
        UUID previousTopCategoryId = previousTop != null ? previousTop.getCategoryId() : null;

        MerchantCategoryLearning target = existingPairs.stream()
                .filter(p -> p.getCategoryId().equals(categoryId))
                .findFirst()
                // Should never happen -- ensurePairExists just guaranteed this row exists. Kept
                // as a defensive fallback (not a throw) for a genuinely different, undocumented
                // race this fix does not claim to close: a concurrent undo()/reset() deleting the
                // row between this method's ensure and its read. Out of BH-053's scope, and this
                // preserves the pre-fix behavior of tolerating it rather than turning it into a 500.
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
    public LearningResult undo(UUID userId, UUID merchantId, UUID actingAdminId) {
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
        //
        // actorId is the second half of that fix: the entry above records that the action happened
        // and to whose data, this records WHICH ADMIN did it. Without it an admin action is
        // indistinguishable from the user's own, which is the question an audit trail exists to
        // answer. Matches the pattern in AdminUserService.
        auditService.record(userId, "MERCHANT_LEARNING_UNDONE", "Merchant", merchantId,
                Map.of("actorId", actingAdminId.toString()));

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
    public MerchantLearningAudit reset(UUID userId, UUID merchantId, UUID actingAdminId) {
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

        // See undo()'s own comment above -- same gap, same fix, including actorId.
        auditService.record(userId, "MERCHANT_LEARNING_RESET", "Merchant", merchantId,
                Map.of("actorId", actingAdminId.toString()));

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

    /**
     * Everything the Learning Engine has to do before a category row can legally be deleted.
     * Called by {@code CategoryService.delete} inside that method's own transaction.
     *
     * <p>Three tables reference {@code categories(id)} from this module, and the original design's
     * FK audit missed all three:
     *
     * <ul>
     *   <li>{@code merchant_learning_audit.previous_category_id} / {@code new_category_id} --
     *       nullable, no explicit {@code ON DELETE}, therefore {@code NO ACTION}. Postgres REFUSES
     *       the delete outright. This is not an edge case: an audit row is written on every manual
     *       recategorization, so essentially every category a user has actually used is
     *       undeletable until these are cleared. Cleared to NULL rather than repointed -- see
     *       {@code MerchantLearningAuditRepository.clearPreviousCategoryReferences} for why
     *       rewriting an audit trail to name a category the user never chose is the wrong answer.
     *   <li>{@code merchant_category_learning.category_id} -- {@code ON DELETE CASCADE}. Does not
     *       block the delete, but silently throws away the merchant training data ("Blinkit means
     *       Groceries, confirmed 14 times") that the user is in the middle of asking us to MOVE,
     *       not discard. Repointed at the reassignment target below.
     *   <li>{@code merchant_learning_events.category_id} -- {@code ON DELETE CASCADE}, and
     *       deliberately left to cascade. That table is the durable retry queue for not-yet-applied
     *       learning (V62), and its own migration comment states the intent directly: an event
     *       naming a category that no longer exists "is not retryable, it is meaningless."
     *       Repointing a queued event at a category the user never chose for it would apply a
     *       confirmation they never made. Telemetry/queue state, not app-relied-upon state.
     * </ul>
     *
     * <p><b>Merge semantics when both categories know the same merchant.</b>
     * {@code UNIQUE(user_id, merchant_id, category_id)} means a straight UPDATE would violate the
     * constraint whenever the target already has its own row for that merchant. In that case the
     * source row's {@code confirmationCount} is ADDED to the target's and the source is deleted,
     * rather than the source simply being discarded: the user is reassigning those transactions to
     * the target, so those confirmations genuinely now belong to it, and dropping them would
     * under-weight the target in {@code ConfidenceEngine.topCategory}. The later of the two
     * {@code lastConfirmedAt} values wins. Confidence is recomputed per affected merchant
     * afterwards so the distribution still sums correctly.
     *
     * @param reassignTo the delete's reassignment target, or {@code null} when the category is
     *                   being deleted with no target (only legal when it has no dependents). With
     *                   no target there is nowhere to move the training data to, so the learning
     *                   rows are left to CASCADE -- the audit clearing above still has to happen
     *                   either way, since that is what actually blocks the delete.
     */
    @Transactional
    public void onCategoryDeleted(UUID userId, UUID deletedCategoryId, UUID reassignTo) {
        if (reassignTo != null && !reassignTo.equals(deletedCategoryId)) {
            repointCategory(userId, deletedCategoryId, reassignTo);
        }
        auditRepository.clearPreviousCategoryReferences(userId, deletedCategoryId);
        auditRepository.clearNewCategoryReferences(userId, deletedCategoryId);
    }

    /** @see #onCategoryDeleted */
    private void repointCategory(UUID userId, UUID fromCategoryId, UUID toCategoryId) {
        List<MerchantCategoryLearning> sources = learningRepository.findByUserIdAndCategoryId(userId, fromCategoryId);
        Set<UUID> touchedMerchantIds = new HashSet<>();

        for (MerchantCategoryLearning source : sources) {
            touchedMerchantIds.add(source.getMerchantId());
            Optional<MerchantCategoryLearning> existingTarget = learningRepository
                    .findByUserIdAndMerchantIdAndCategoryId(userId, source.getMerchantId(), toCategoryId);

            if (existingTarget.isPresent()) {
                MerchantCategoryLearning target = existingTarget.get();
                target.setConfirmationCount(target.getConfirmationCount() + source.getConfirmationCount());
                if (source.getLastConfirmedAt().isAfter(target.getLastConfirmedAt())) {
                    target.setLastConfirmedAt(source.getLastConfirmedAt());
                }
                target.setUpdatedAt(Instant.now());
                learningRepository.save(target);
                learningRepository.delete(source);
            } else {
                source.setCategoryId(toCategoryId);
                source.setUpdatedAt(Instant.now());
                learningRepository.save(source);
            }
        }

        // Deleted/merged rows must be gone from the DB's point of view before the per-merchant
        // re-read below, or a merged-away source row comes back from the persistence context and
        // gets its confidence recomputed as if it still existed.
        learningRepository.flush();
        for (UUID merchantId : touchedMerchantIds) {
            recomputeAndSave(learningRepository.findByUserIdAndMerchantId(userId, merchantId));
        }
    }

    private void recomputeAndSave(List<MerchantCategoryLearning> pairs) {
        var confidenceByCategory = confidenceEngine.recomputeDistribution(pairs);
        for (MerchantCategoryLearning pair : pairs) {
            pair.setConfidence(confidenceByCategory.getOrDefault(pair.getCategoryId(), 0));
        }
        learningRepository.saveAll(pairs);
    }
}
