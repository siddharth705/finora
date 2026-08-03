package com.finora.service;

import com.finora.dto.LearningDto;
import com.finora.entity.Category;
import com.finora.entity.Merchant;
import com.finora.entity.MerchantCategoryLearning;
import com.finora.entity.MerchantLearningAudit;
import com.finora.exception.ApiException;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.repository.MerchantLearningAuditRepository;
import com.finora.repository.MerchantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Covers every Milestone A acceptance criterion from the spec: first confirmation, reinforcing
 * confirmation, conflicting confirmation (CORRECTED audit), and undo after each of those.
 *
 * The spec's Failure & Recovery criterion ("a learning-update failure does not roll back the
 * transaction's already-applied category") is a cross-service integration behavior that only
 * exists once MerchantLearningService is actually wired into TransactionService — that's
 * Milestone B scope per the spec, not testable in isolation here, and is called out as a TODO
 * on confirm() itself rather than silently skipped.
 *
 * These are unit tests against mocked repositories, deliberately mirroring the existing project
 * pattern (CategorizationServiceTest, ReconciliationServiceTest). A Testcontainers-backed
 * integration test exercising confirm()/undo() against real Postgres would add confidence beyond
 * these — the spec explicitly calls for that level of testing on merge/undo in Milestone C, and
 * the same argument applies here; noted as a reasonable follow-up, not blocking this milestone.
 */
class MerchantLearningServiceTest {

    private MerchantCategoryLearningRepository learningRepository;
    private MerchantLearningAuditRepository auditRepository;
    private MerchantRepository merchantRepository;
    private CategoryRepository categoryRepository;
    private AuditService auditService;
    private MerchantLearningService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID merchantId = UUID.randomUUID();
    private final UUID shoppingCategoryId = UUID.randomUUID();
    private final UUID electronicsCategoryId = UUID.randomUUID();

    private List<MerchantCategoryLearning> distribution;
    private List<MerchantLearningAudit> auditHistory;

    @BeforeEach
    void setUp() {
        learningRepository = mock(MerchantCategoryLearningRepository.class);
        auditRepository = mock(MerchantLearningAuditRepository.class);
        merchantRepository = mock(MerchantRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        auditService = mock(AuditService.class);
        service = new MerchantLearningService(learningRepository, auditRepository, merchantRepository, categoryRepository, new ConfidenceEngine(), auditService);

        distribution = new ArrayList<>();
        auditHistory = new ArrayList<>();

        // Mockito returns the exact list reference configured here on every call, so mutations
        // the service makes in-place (add/remove) are visible on subsequent calls within the
        // same test — this stands in for "the database" without a real persistence layer.
        when(learningRepository.findByUserIdAndMerchantId(eq(userId), eq(merchantId))).thenReturn(distribution);
        when(auditRepository.findByUserIdAndMerchantIdOrderByCreatedAtDesc(userId, merchantId)).thenAnswer(inv -> {
            List<MerchantLearningAudit> reversed = new ArrayList<>(auditHistory);
            java.util.Collections.reverse(reversed);
            return reversed;
        });
        when(auditRepository.save(any(MerchantLearningAudit.class))).thenAnswer(inv -> {
            MerchantLearningAudit a = inv.getArgument(0);
            // Bug fix: no test double for @GeneratedValue -- a real Hibernate save() populates the
            // id on the entity instance after INSERT; this mock previously didn't, so anything
            // reading a.getId() after save() (timeline()'s new id field, see LearningDto's own doc
            // comment) always saw null here even though production never would. Assigning one here,
            // same pattern as RuleServiceTest's save() stub, makes the mock behave like the real
            // repository instead of just being "good enough to not throw."
            if (a.getId() == null) ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
            auditHistory.add(a);
            return a;
        });
        // Bug fix: timeline() (added for the Learning Engine page) reads auditRepository.findByUserId
        // (unlike undo()/confirm(), which use the per-merchant query stubbed above) -- this was never
        // stubbed here, so with Mockito's empty-list default for an unstubbed List-returning method,
        // every timeline() test below was silently exercising "no audit history exists" regardless of
        // what confirm()/reset() had just done. timeline_resolvesMerchantAndCategoryNames_newestFirst
        // would have failed its hasSize(2) assertion; timeline_unknownMerchant_fallsBackRatherThanThrowing
        // would have thrown IndexOutOfBoundsException on .get(0) -- neither was caught because this
        // suite hasn't compiled since before that page existed. summary()'s tests already stubbed this
        // correctly per-test; moving it here for every test removes that duplication too.
        when(auditRepository.findByUserId(userId)).thenAnswer(inv -> auditHistory);
        // reset()'s deleteAll -- mirrors undo()'s in-place pairs.remove(pair) above: the mock
        // returns the SAME `distribution` list reference every time, so mutating it here is what
        // makes a follow-up findByUserIdAndMerchantId() call see the deletion, standing in for a
        // real DELETE statement without a persistence layer.
        doAnswer(inv -> {
            List<?> toDelete = inv.getArgument(0);
            distribution.removeAll(toDelete);
            return null;
        }).when(learningRepository).deleteAll(any(List.class));
    }

    @Test
    void confirm_firstConfirmationEver_createsNewPairAtFullConfidence_andAuditsAsLearned() {
        var result = service.confirm(userId, merchantId, shoppingCategoryId);

        assertThat(result.distribution()).hasSize(1);
        assertThat(result.distribution().get(0).getCategoryId()).isEqualTo(shoppingCategoryId);
        assertThat(result.distribution().get(0).getConfirmationCount()).isEqualTo(1);
        assertThat(result.distribution().get(0).getConfidence()).isEqualTo(100); // only category so far = 100% share

        assertThat(result.auditEntry().getAction()).isEqualTo(MerchantLearningAudit.Action.LEARNED);
        assertThat(result.auditEntry().getPreviousCategoryId()).isNull();
        assertThat(result.auditEntry().getNewCategoryId()).isEqualTo(shoppingCategoryId);
    }

    @Test
    void confirm_reinforcingConfirmation_incrementsCountAndStaysLearned_notCorrected() {
        service.confirm(userId, merchantId, shoppingCategoryId);
        var result = service.confirm(userId, merchantId, shoppingCategoryId);

        assertThat(result.distribution()).hasSize(1);
        assertThat(result.distribution().get(0).getConfirmationCount()).isEqualTo(2);
        assertThat(result.distribution().get(0).getConfidence()).isEqualTo(100);
        assertThat(result.auditEntry().getAction()).isEqualTo(MerchantLearningAudit.Action.LEARNED);
    }

    @Test
    void confirm_conflictingConfirmation_createsSecondPair_recomputesSharedConfidence_auditsAsCorrected() {
        service.confirm(userId, merchantId, shoppingCategoryId); // count=1, 100%
        var result = service.confirm(userId, merchantId, electronicsCategoryId); // now a genuine conflict

        assertThat(result.distribution()).hasSize(2);
        // Both categories now have 1 confirmation each -> 50/50 share.
        assertThat(result.distribution()).allSatisfy(pair -> assertThat(pair.getConfidence()).isEqualTo(50));

        assertThat(result.auditEntry().getAction()).isEqualTo(MerchantLearningAudit.Action.CORRECTED);
        assertThat(result.auditEntry().getPreviousCategoryId()).isEqualTo(shoppingCategoryId);
        assertThat(result.auditEntry().getNewCategoryId()).isEqualTo(electronicsCategoryId);
    }

    @Test
    void confirm_repeatedConfirmationsAcrossTwoCategories_reflectRealEvidenceShare() {
        service.confirm(userId, merchantId, shoppingCategoryId);
        service.confirm(userId, merchantId, shoppingCategoryId);
        service.confirm(userId, merchantId, shoppingCategoryId); // Shopping now confirmed 3x
        var result = service.confirm(userId, merchantId, electronicsCategoryId); // Electronics 1x

        var shopping = result.distribution().stream().filter(p -> p.getCategoryId().equals(shoppingCategoryId)).findFirst().orElseThrow();
        var electronics = result.distribution().stream().filter(p -> p.getCategoryId().equals(electronicsCategoryId)).findFirst().orElseThrow();

        assertThat(shopping.getConfirmationCount()).isEqualTo(3);
        assertThat(electronics.getConfirmationCount()).isEqualTo(1);
        assertThat(shopping.getConfidence()).isEqualTo(75); // 3/4
        assertThat(electronics.getConfidence()).isEqualTo(25); // 1/4
    }

    @Test
    void undo_afterFirstConfirmation_removesThePairEntirely_andAuditsUndone() {
        service.confirm(userId, merchantId, shoppingCategoryId);
        var result = service.undo(userId, merchantId);

        assertThat(result.distribution()).isEmpty(); // count dropped to 0 -> pair removed
        assertThat(result.auditEntry().getAction()).isEqualTo(MerchantLearningAudit.Action.UNDONE);
        assertThat(result.auditEntry().getPreviousCategoryId()).isEqualTo(shoppingCategoryId);
        assertThat(result.auditEntry().getNewCategoryId()).isNull();
        // Bug fix regression: undo() used to never call AuditService at all, so an admin's undo
        // (the only way this is reachable today -- see AdminUserMerchantController) left zero
        // trace in the general activity feed.
        verify(auditService).record(userId, "MERCHANT_LEARNING_UNDONE", "Merchant", merchantId);
    }

    @Test
    void undo_afterReinforcingConfirmation_decrementsCountRatherThanRemoving() {
        service.confirm(userId, merchantId, shoppingCategoryId);
        service.confirm(userId, merchantId, shoppingCategoryId); // count=2
        var result = service.undo(userId, merchantId);

        assertThat(result.distribution()).hasSize(1);
        assertThat(result.distribution().get(0).getConfirmationCount()).isEqualTo(1);
        assertThat(result.distribution().get(0).getConfidence()).isEqualTo(100);
    }

    @Test
    void undo_afterConflictingConfirmation_revertsTheNewlyCorrectedCategory_notTheOriginal() {
        service.confirm(userId, merchantId, shoppingCategoryId);
        service.confirm(userId, merchantId, electronicsCategoryId); // conflict; Electronics is now most-recent
        var result = service.undo(userId, merchantId);

        assertThat(result.distribution()).hasSize(1);
        assertThat(result.distribution().get(0).getCategoryId()).isEqualTo(shoppingCategoryId);
        assertThat(result.distribution().get(0).getConfidence()).isEqualTo(100); // back to sole category
    }

    @Test
    void undo_withNoLearningHistory_throwsClearError() {
        assertThatThrownBy(() -> service.undo(userId, merchantId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No learning history");
    }

    @Test
    void undo_calledTwiceInARow_secondCallThrowsRatherThanAttemptingARedo() {
        service.confirm(userId, merchantId, shoppingCategoryId);
        service.undo(userId, merchantId);

        assertThatThrownBy(() -> service.undo(userId, merchantId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("can't be undone");
    }

    /**
     * Bug fix: undo()'s "nothing well-defined to revert" guard only checked for UNDONE/MERGED,
     * not RESET -- even though a RESET has that exact same property (worse, actually: reset()
     * deletes the entire distribution unconditionally, and always audits newCategoryId=null, so
     * there's no single pair for undo() to find and no confirmation count to give back). Before
     * the fix, this slipped past the guard and silently wrote a fresh, misleading UNDONE audit
     * entry that reverted nothing at all.
     */
    @Test
    void undo_afterAReset_throwsRatherThanSilentlyNoOpingWithAMisleadingUndoneEntry() {
        service.confirm(userId, merchantId, shoppingCategoryId);
        service.reset(userId, merchantId);

        assertThatThrownBy(() -> service.undo(userId, merchantId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("can't be undone");

        // The failed attempt must not have written a spurious UNDONE entry on top of the RESET.
        assertThat(auditHistory).hasSize(2); // LEARNED (from confirm), then RESET -- nothing more
        assertThat(auditHistory.get(auditHistory.size() - 1).getAction()).isEqualTo(MerchantLearningAudit.Action.RESET);
    }

    /**
     * Regression test for a real cross-tenant data leak found during review:
     * MerchantLearningAuditRepository previously exposed findByMerchantIdOrderByCreatedAtDesc
     * with no userId scoping at all, so undo() for one user could read (and revert against)
     * another user's audit history for a merchant with the same ID. Sets up two entirely
     * separate users' distributions/audit histories and confirms undo() for one never touches
     * the other's data.
     */
    @Test
    void undo_forAUserWithNoHistoryOnThisMerchant_throwsRatherThanLeakingAnotherUsersHistory() {
        UUID otherUserId = UUID.randomUUID();
        List<MerchantCategoryLearning> otherUsersDistribution = new ArrayList<>();
        List<MerchantLearningAudit> otherUsersAuditHistory = new ArrayList<>();

        when(learningRepository.findByUserIdAndMerchantId(eq(otherUserId), eq(merchantId)))
                .thenReturn(otherUsersDistribution);
        when(auditRepository.findByUserIdAndMerchantIdOrderByCreatedAtDesc(otherUserId, merchantId))
                .thenAnswer(inv -> {
                    List<MerchantLearningAudit> reversed = new ArrayList<>(otherUsersAuditHistory);
                    java.util.Collections.reverse(reversed);
                    return reversed;
                });
        when(auditRepository.save(any(MerchantLearningAudit.class))).thenAnswer(inv -> {
            MerchantLearningAudit a = inv.getArgument(0);
            if (a.getUserId().equals(otherUserId)) {
                otherUsersAuditHistory.add(a);
            } else {
                auditHistory.add(a);
            }
            return a;
        });

        // The real user (userId) confirms and builds up their own history.
        service.confirm(userId, merchantId, shoppingCategoryId);

        // The other user has never confirmed anything for this same merchantId -- their own
        // scoped history is empty, so undo() must throw for them rather than falling through
        // to (or ever touching) the real user's history above.
        assertThatThrownBy(() -> service.undo(otherUserId, merchantId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("No learning history");

        // And the real user's own history/distribution must be completely untouched by that
        // failed attempt.
        assertThat(distribution).hasSize(1);
        assertThat(distribution.get(0).getConfirmationCount()).isEqualTo(1);
    }

    // --- reset() (Financial Intelligence Workspace, Learning Engine module) ---

    @Test
    void reset_wipesTheWholeDistribution_andAuditsAsReset() {
        service.confirm(userId, merchantId, shoppingCategoryId);
        service.confirm(userId, merchantId, electronicsCategoryId); // two pairs now, Electronics is top

        MerchantLearningAudit result = service.reset(userId, merchantId);

        assertThat(distribution).isEmpty();
        assertThat(result.getAction()).isEqualTo(MerchantLearningAudit.Action.RESET);
        assertThat(result.getPreviousCategoryId()).isEqualTo(electronicsCategoryId); // whatever was top going in
        assertThat(result.getNewCategoryId()).isNull();
        // Same bug-fix regression as undo() above.
        verify(auditService).record(userId, "MERCHANT_LEARNING_RESET", "Merchant", merchantId);
    }

    @Test
    void reset_withNoLearningHistory_throwsClearError() {
        assertThatThrownBy(() -> service.reset(userId, merchantId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no learning history");
    }

    @Test
    void reset_doesNotTouchAnotherMerchantsDistribution() {
        UUID otherMerchantId = UUID.randomUUID();
        List<MerchantCategoryLearning> otherDistribution = new ArrayList<>();
        when(learningRepository.findByUserIdAndMerchantId(eq(userId), eq(otherMerchantId))).thenReturn(otherDistribution);

        service.confirm(userId, merchantId, shoppingCategoryId);
        service.confirm(userId, otherMerchantId, electronicsCategoryId);

        service.reset(userId, merchantId);

        assertThat(distribution).isEmpty();
        assertThat(otherDistribution).hasSize(1); // untouched
    }

    // --- timeline()/summary() (Financial Intelligence Workspace, Learning Engine module) ---

    private Merchant merchant(UUID id, String name) {
        Merchant m = new Merchant();
        ReflectionTestUtils.setField(m, "id", id);
        m.setUserId(userId);
        m.setCanonicalName(name);
        return m;
    }

    private Category category(UUID id, String name) {
        Category c = new Category();
        ReflectionTestUtils.setField(c, "id", id);
        c.setUserId(userId);
        c.setName(name);
        return c;
    }

    @Test
    void timeline_resolvesMerchantAndCategoryNames_newestFirst() {
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of(merchant(merchantId, "Swiggy")));
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of(
                category(shoppingCategoryId, "Shopping"), category(electronicsCategoryId, "Electronics")));

        service.confirm(userId, merchantId, shoppingCategoryId); // LEARNED
        service.confirm(userId, merchantId, electronicsCategoryId); // CORRECTED, most recent

        List<LearningDto.TimelineEntry> timeline = service.timeline(userId);

        assertThat(timeline).hasSize(2);
        assertThat(timeline.get(0).action()).isEqualTo("CORRECTED"); // newest first
        assertThat(timeline.get(0).merchantName()).isEqualTo("Swiggy");
        assertThat(timeline.get(0).previousCategoryName()).isEqualTo("Shopping");
        assertThat(timeline.get(0).newCategoryName()).isEqualTo("Electronics");
        assertThat(timeline.get(1).action()).isEqualTo("LEARNED");
    }

    @Test
    void timeline_surfacesEachEntrysOwnId_notJustItsPositionInTheList() {
        // Bug fix: TimelineEntry originally had no id field at all, so the frontend's activity
        // list had to key its rows on array index -- which breaks the moment the list reorders,
        // e.g. right after this exact scenario (a Reset Learning call re-fetching a re-sorted
        // timeline). Each entry's id must be its own MerchantLearningAudit row's id, not null and
        // not shared between rows.
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of(merchant(merchantId, "Swiggy")));
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of(category(shoppingCategoryId, "Shopping")));

        service.confirm(userId, merchantId, shoppingCategoryId);
        service.reset(userId, merchantId);

        List<LearningDto.TimelineEntry> timeline = service.timeline(userId);

        assertThat(timeline).hasSize(2);
        assertThat(timeline.get(0).id()).isNotNull();
        assertThat(timeline.get(1).id()).isNotNull();
        assertThat(timeline.get(0).id()).isNotEqualTo(timeline.get(1).id());
    }

    @Test
    void timeline_unknownMerchant_fallsBackRatherThanThrowing() {
        // Defensive: a merchant deleted after its learning history was written shouldn't break
        // the whole timeline (Merchant isn't soft-deletable the way Account is -- see Merchant's
        // own class comment -- so this is a real, reachable case, not just paranoia).
        when(merchantRepository.findByUserId(userId)).thenReturn(List.of());
        when(categoryRepository.findByUserId(userId)).thenReturn(List.of(category(shoppingCategoryId, "Shopping")));

        service.confirm(userId, merchantId, shoppingCategoryId);

        assertThat(service.timeline(userId).get(0).merchantName()).isEqualTo("Unknown merchant");
    }

    @Test
    void summary_countsDistinctLearnedMerchants_andLifetimeAuditCounts() {
        UUID otherMerchantId = UUID.randomUUID();
        List<MerchantCategoryLearning> otherDistribution = new ArrayList<>();
        when(learningRepository.findByUserIdAndMerchantId(eq(userId), eq(otherMerchantId))).thenReturn(otherDistribution);
        when(learningRepository.findByUserId(userId)).thenAnswer(inv -> {
            List<MerchantCategoryLearning> all = new ArrayList<>(distribution);
            all.addAll(otherDistribution);
            return all;
        });

        service.confirm(userId, merchantId, shoppingCategoryId); // LEARNED
        service.confirm(userId, merchantId, electronicsCategoryId); // CORRECTED
        service.confirm(userId, otherMerchantId, shoppingCategoryId); // LEARNED (different merchant)

        LearningDto.Summary summary = service.summary(userId);

        assertThat(summary.learnedMerchants()).isEqualTo(2);
        assertThat(summary.totalConfirmations()).isEqualTo(3); // 2 LEARNED + 1 CORRECTED
        assertThat(summary.correctedCount()).isEqualTo(1);
        assertThat(summary.resetCount()).isZero();
    }

    @Test
    void summary_afterReset_resetCountIncrements_learnedMerchantsExcludesIt() {
        when(learningRepository.findByUserId(userId)).thenAnswer(inv -> new ArrayList<>(distribution));

        service.confirm(userId, merchantId, shoppingCategoryId);
        service.reset(userId, merchantId);

        LearningDto.Summary summary = service.summary(userId);

        assertThat(summary.learnedMerchants()).isZero(); // distribution is empty after reset
        assertThat(summary.resetCount()).isEqualTo(1);
        assertThat(summary.totalConfirmations()).isEqualTo(1); // the LEARNED entry is still in the audit trail
    }
}
