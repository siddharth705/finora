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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests against mocked repositories, mirroring the existing project pattern
 * (MerchantLearningServiceTest, ReconciliationServiceTest). The backing lists below stand in for
 * "the database": repository stubs return/mutate the exact same list references the service
 * operates on, so saveAll/delete calls the service makes are visible on the next stubbed read
 * within the same test, without needing a real persistence layer.
 *
 * Per the spec's own Milestone C guidance (§11: "Integration tests... cover merge and undo
 * against a real Postgres instance, not mocks — these two operations are exactly the kind of
 * multi-table consistency logic that's easy to get subtly wrong"), a Testcontainers-backed
 * integration test for merge() would add real confidence beyond what's here — noted as a
 * reasonable follow-up, not blocking this milestone (same position taken in
 * MerchantLearningServiceTest's own class comment for confirm()/undo()).
 */
class MerchantServiceTest {

    private MerchantRepository merchantRepository;
    private MerchantAliasRepository merchantAliasRepository;
    private MerchantCategoryLearningRepository learningRepository;
    private MerchantLearningAuditRepository auditRepository;
    private CategoryRepository categoryRepository;
    private TransactionRepository transactionRepository;
    private AuditService auditService;
    private MerchantService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID shoppingCategoryId = UUID.randomUUID();
    private final UUID electronicsCategoryId = UUID.randomUUID();
    private final UUID actingAdminId = UUID.randomUUID();

    private List<Merchant> merchants;
    private List<MerchantAlias> aliases;
    private List<MerchantCategoryLearning> learningPairs;
    private List<MerchantLearningAudit> auditEntries;
    private List<Transaction> transactions;

    @BeforeEach
    void setUp() {
        merchantRepository = mock(MerchantRepository.class);
        merchantAliasRepository = mock(MerchantAliasRepository.class);
        learningRepository = mock(MerchantCategoryLearningRepository.class);
        auditRepository = mock(MerchantLearningAuditRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        auditService = mock(AuditService.class);
        service = new MerchantService(merchantRepository, merchantAliasRepository, learningRepository,
                auditRepository, categoryRepository, transactionRepository, new ConfidenceEngine(),
                auditService);

        merchants = new ArrayList<>();
        aliases = new ArrayList<>();
        learningPairs = new ArrayList<>();
        auditEntries = new ArrayList<>();
        transactions = new ArrayList<>();

        when(merchantRepository.findByIdAndUserId(any(), any())).thenAnswer(inv -> merchants.stream()
                .filter(m -> m.getId().equals(inv.getArgument(0)) && m.getUserId().equals(inv.getArgument(1)))
                .findFirst());
        when(merchantRepository.findByUserId(any())).thenAnswer(inv -> merchants.stream()
                .filter(m -> m.getUserId().equals(inv.getArgument(0))).toList());
        when(merchantRepository.save(any(Merchant.class))).thenAnswer(inv -> inv.getArgument(0));
        // delete(T) returns void -- same doAnswer().when() requirement as deleteAll above. Without
        // this stub the mock's delete() call would silently no-op, and merge()'s "absorbed
        // merchant is actually removed" behavior would go unverified rather than tested.
        org.mockito.Mockito.doAnswer(inv -> {
            merchants.remove((Merchant) inv.getArgument(0));
            return null;
        }).when(merchantRepository).delete(any());

        when(merchantAliasRepository.findByMerchantId(any())).thenAnswer(inv -> new ArrayList<>(aliases.stream()
                .filter(a -> a.getMerchantId().equals(inv.getArgument(0))).toList()));
        when(merchantAliasRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        when(learningRepository.findByUserIdAndMerchantId(any(), any())).thenAnswer(inv -> new ArrayList<>(learningPairs.stream()
                .filter(p -> p.getUserId().equals(inv.getArgument(0)) && p.getMerchantId().equals(inv.getArgument(1)))
                .toList()));
        // Backs listForUser()'s bulk-fetch-then-group-in-memory N+1 fix -- without this stub,
        // Mockito's default ReturnsEmptyValues answer silently returns an empty list for every
        // call, so every merchant would appear to have no learning distribution at all.
        when(learningRepository.findByUserId(any())).thenAnswer(inv -> new ArrayList<>(learningPairs.stream()
                .filter(p -> p.getUserId().equals(inv.getArgument(0)))
                .toList()));
        when(learningRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
        // deleteAll(Iterable) returns void -- can't be stubbed via when(mock.method()), needs
        // doAnswer().when(mock).method() instead.
        org.mockito.Mockito.doAnswer(inv -> {
            List<MerchantCategoryLearning> toRemove = inv.getArgument(0);
            learningPairs.removeAll(toRemove);
            return null;
        }).when(learningRepository).deleteAll(any());

        when(auditRepository.findByUserIdAndMerchantIdOrderByCreatedAtDesc(any(), any())).thenAnswer(inv -> {
            List<MerchantLearningAudit> matched = new ArrayList<>(auditEntries.stream()
                    .filter(a -> a.getUserId().equals(inv.getArgument(0)) && a.getMerchantId().equals(inv.getArgument(1)))
                    .toList());
            java.util.Collections.reverse(matched); // newest first, same convention as MerchantLearningAuditRepository
            return matched;
        });
        when(auditRepository.save(any(MerchantLearningAudit.class))).thenAnswer(inv -> {
            MerchantLearningAudit a = inv.getArgument(0);
            auditEntries.add(a);
            return a;
        });
        when(auditRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        when(categoryRepository.findByUserId(any())).thenAnswer(inv -> List.of(
                category(shoppingCategoryId, "Shopping"), category(electronicsCategoryId, "Electronics")));

        when(transactionRepository.findByUserIdAndMerchantId(any(), any())).thenAnswer(inv -> new ArrayList<>(transactions.stream()
                .filter(t -> t.getUserId().equals(inv.getArgument(0)) && inv.getArgument(1).equals(t.getMerchantId()))
                .toList()));
        when(transactionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private Category category(UUID id, String name) {
        Category c = new Category();
        ReflectionTestUtils.setField(c, "id", id);
        c.setUserId(userId);
        c.setName(name);
        return c;
    }

    private Merchant merchant(String name) {
        Merchant m = new Merchant();
        ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
        m.setUserId(userId);
        m.setCanonicalName(name);
        merchants.add(m);
        return m;
    }

    private MerchantAlias alias(UUID merchantId, String normalized) {
        MerchantAlias a = new MerchantAlias();
        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
        a.setMerchantId(merchantId);
        a.setUserId(userId);
        a.setNormalizedAlias(normalized);
        aliases.add(a);
        return a;
    }

    private MerchantCategoryLearning pair(UUID merchantId, UUID categoryId, int count, int confidence) {
        MerchantCategoryLearning p = new MerchantCategoryLearning();
        ReflectionTestUtils.setField(p, "id", UUID.randomUUID());
        p.setUserId(userId);
        p.setMerchantId(merchantId);
        p.setCategoryId(categoryId);
        p.setConfirmationCount(count);
        p.setConfidence(confidence);
        learningPairs.add(p);
        return p;
    }

    private Transaction transaction(UUID merchantId) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        t.setUserId(userId);
        t.setMerchantId(merchantId);
        transactions.add(t);
        return t;
    }

    // --- list / get ---

    @Test
    void listForUser_returnsDistributionSortedByCountDescending_withTopCategoryFromConfidenceEngine() {
        Merchant amazon = merchant("Amazon");
        pair(amazon.getId(), electronicsCategoryId, 34, 24);
        pair(amazon.getId(), shoppingCategoryId, 147, 76);

        List<MerchantDto> result = service.listForUser(userId);

        assertThat(result).hasSize(1);
        MerchantDto dto = result.get(0);
        assertThat(dto.topCategory()).isEqualTo("Shopping"); // higher confirmationCount wins
        assertThat(dto.topCategoryConfidence()).isEqualTo(76);
        assertThat(dto.distribution()).extracting(MerchantDto.DistributionEntry::category)
                .containsExactly("Shopping", "Electronics"); // sorted by confirmationCount desc
    }

    @Test
    void listForUser_merchantWithNoConfirmationsYet_hasNullTopCategoryAndEmptyDistribution() {
        merchant("Brand New Merchant");

        MerchantDto dto = service.listForUser(userId).get(0);

        assertThat(dto.topCategory()).isNull();
        assertThat(dto.topCategoryConfidence()).isNull();
        assertThat(dto.distribution()).isEmpty();
    }

    @Test
    void get_merchantNotOwnedByUser_throwsNotFound() {
        Merchant somebodyElses = merchant("Someone Else's Merchant");
        somebodyElses.setUserId(UUID.randomUUID());

        assertThatThrownBy(() -> service.get(userId, somebodyElses.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");
    }

    // --- transactionsFor (Financial Intelligence Workspace, Module 2) ---

    @Test
    void transactionsFor_returnsOnlyThisMerchantsTransactions_newestFirst_withCategoryNameResolved() {
        Merchant amazon = merchant("Amazon");
        Merchant swiggy = merchant("Swiggy");

        Transaction older = transaction(amazon.getId());
        older.setTxnDate(java.time.LocalDate.of(2026, 1, 1));
        older.setCategoryId(shoppingCategoryId);
        older.setTxnType(Transaction.Type.EXPENSE);

        Transaction newer = transaction(amazon.getId());
        newer.setTxnDate(java.time.LocalDate.of(2026, 6, 1));
        newer.setCategoryId(electronicsCategoryId);
        newer.setTxnType(Transaction.Type.EXPENSE);

        Transaction otherMerchants = transaction(swiggy.getId());
        otherMerchants.setTxnDate(java.time.LocalDate.of(2026, 3, 1));
        otherMerchants.setTxnType(Transaction.Type.EXPENSE);

        List<TransactionDto> result = service.transactionsFor(userId, amazon.getId());

        assertThat(result).hasSize(2); // Swiggy's transaction excluded
        assertThat(result).extracting(TransactionDto::date).containsExactly(newer.getTxnDate(), older.getTxnDate());
        assertThat(result.get(0).categoryName()).isEqualTo("Electronics");
        assertThat(result.get(1).categoryName()).isEqualTo("Shopping");
    }

    @Test
    void transactionsFor_merchantNotOwnedByUser_throwsNotFound() {
        Merchant somebodyElses = merchant("Someone Else's Merchant");
        somebodyElses.setUserId(UUID.randomUUID());

        assertThatThrownBy(() -> service.transactionsFor(userId, somebodyElses.getId()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");
    }

    // --- rename ---

    @Test
    void rename_updatesOnlySuppliedFields() {
        Merchant m = merchant("Amazn");
        m.setWebsite("https://old.example");

        MerchantDto updated = service.rename(userId, m.getId(), new MerchantDto.UpdateRequest("Amazon", null), actingAdminId);

        assertThat(updated.canonicalName()).isEqualTo("Amazon");
        assertThat(m.getWebsite()).isEqualTo("https://old.example"); // untouched -- null means "don't change"
    }

    @Test
    void rename_blankCanonicalName_throws() {
        Merchant m = merchant("Amazon");

        assertThatThrownBy(() -> service.rename(userId, m.getId(), new MerchantDto.UpdateRequest("   ", null), actingAdminId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void rename_recordsAGeneralActivityFeedEntry() {
        // Regression test for a real gap found during the Workspace kickoff audit: MerchantService
        // never called AuditService at all, so a rename was invisible in every audit trail --
        // merchant_learning_audit only tracks LEARNED/CORRECTED/UNDONE/MERGED, never a rename.
        Merchant m = merchant("Amazn");

        service.rename(userId, m.getId(), new MerchantDto.UpdateRequest("Amazon", null), actingAdminId);

        org.mockito.Mockito.verify(auditService).record(eq(userId), eq("MERCHANT_UPDATED"), eq("Merchant"), eq(m.getId()), any());
    }

    @Test
    void rename_recordsActorIdInAuditMetadata() {
        Merchant m = merchant("Amazn");

        service.rename(userId, m.getId(), new MerchantDto.UpdateRequest("Amazon", null), actingAdminId);

        org.mockito.Mockito.verify(auditService).record(eq(userId), eq("MERCHANT_UPDATED"), eq("Merchant"), eq(m.getId()),
                org.mockito.ArgumentMatchers.argThat(metadata -> actingAdminId.toString().equals(metadata.get("actorId"))));
    }

    // --- merge ---

    @Test
    void merge_intoItself_throws() {
        Merchant m = merchant("Amazon");
        assertThatThrownBy(() -> service.merge(userId, m.getId(), m.getId(), actingAdminId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("itself");
    }

    @Test
    void merge_repointsAliasesAndTransactions_deletesAbsorbedMerchant() {
        Merchant surviving = merchant("Amazon");
        Merchant absorbed = merchant("AMAZON SELLER SERVICES");
        alias(absorbed.getId(), "amazon seller services");
        Transaction t1 = transaction(absorbed.getId());
        Transaction t2 = transaction(absorbed.getId());

        service.merge(userId, surviving.getId(), absorbed.getId(), actingAdminId);

        assertThat(aliases).allSatisfy(a -> assertThat(a.getMerchantId()).isEqualTo(surviving.getId()));
        assertThat(t1.getMerchantId()).isEqualTo(surviving.getId());
        assertThat(t2.getMerchantId()).isEqualTo(surviving.getId());
        assertThat(merchants).doesNotContain(absorbed); // deleted
    }

    @Test
    void merge_sumsSameCategoryDistributionAcrossBothMerchants_notReplace() {
        Merchant surviving = merchant("Amazon");
        Merchant absorbed = merchant("AMAZON SELLER SERVICES");
        pair(surviving.getId(), shoppingCategoryId, 147, 100);
        pair(absorbed.getId(), shoppingCategoryId, 23, 100);

        MerchantDto result = service.merge(userId, surviving.getId(), absorbed.getId(), actingAdminId);

        assertThat(result.distribution()).hasSize(1);
        assertThat(result.distribution().get(0).confirmationCount()).isEqualTo(170); // 147 + 23, summed not replaced
        assertThat(result.distribution().get(0).confidence()).isEqualTo(100); // only category -> 100% share
    }

    @Test
    void merge_repointsNonConflictingCategoryFromAbsorbedMerchant_ratherThanDroppingIt() {
        Merchant surviving = merchant("Amazon");
        Merchant absorbed = merchant("AMAZON SELLER SERVICES");
        pair(surviving.getId(), shoppingCategoryId, 100, 100);
        pair(absorbed.getId(), electronicsCategoryId, 50, 100); // no conflict -- surviving has no Electronics pair

        MerchantDto result = service.merge(userId, surviving.getId(), absorbed.getId(), actingAdminId);

        assertThat(result.distribution()).hasSize(2);
        var electronics = result.distribution().stream().filter(d -> d.category().equals("Electronics")).findFirst().orElseThrow();
        assertThat(electronics.confirmationCount()).isEqualTo(50);
        // Recomputed confidence: 100 shopping / (100+50) total = 67%, 50/150 = 33%.
        assertThat(electronics.confidence()).isEqualTo(33);
    }

    @Test
    void merge_writesASingleMergedAuditEntryOnTheSurvivingMerchant() {
        Merchant surviving = merchant("Amazon");
        Merchant absorbed = merchant("AMAZON SELLER SERVICES");

        service.merge(userId, surviving.getId(), absorbed.getId(), actingAdminId);

        List<MerchantLearningAudit> survivingAudit = auditEntries.stream()
                .filter(a -> a.getMerchantId().equals(surviving.getId())).toList();
        assertThat(survivingAudit).anySatisfy(a -> assertThat(a.getAction()).isEqualTo(MerchantLearningAudit.Action.MERGED));
    }

    @Test
    void merge_recordsAGeneralActivityFeedEntry_distinctFromTheMergedLearningAuditRow() {
        // Same gap as rename() above -- a merge was previously visible ONLY in
        // merchant_learning_audit (scoped to the surviving merchant's own history), never in the
        // general activity feed ActivityController exposes.
        Merchant surviving = merchant("Amazon");
        Merchant absorbed = merchant("AMAZON SELLER SERVICES");

        service.merge(userId, surviving.getId(), absorbed.getId(), actingAdminId);

        org.mockito.Mockito.verify(auditService).record(eq(userId), eq("MERCHANT_MERGED"), eq("Merchant"), eq(surviving.getId()), any());
    }

    @Test
    void merge_recordsActorIdInAuditMetadata() {
        Merchant surviving = merchant("Amazon");
        Merchant absorbed = merchant("AMAZON SELLER SERVICES");

        service.merge(userId, surviving.getId(), absorbed.getId(), actingAdminId);

        org.mockito.Mockito.verify(auditService).record(eq(userId), eq("MERCHANT_MERGED"), eq("Merchant"), eq(surviving.getId()),
                org.mockito.ArgumentMatchers.argThat(metadata -> actingAdminId.toString().equals(metadata.get("actorId"))));
    }

    @Test
    void merge_preservesTheAbsorbedMerchantsPreMergeAuditHistory_repointedRatherThanLost() {
        Merchant surviving = merchant("Amazon");
        Merchant absorbed = merchant("AMAZON SELLER SERVICES");

        MerchantLearningAudit priorLearn = new MerchantLearningAudit();
        priorLearn.setMerchantId(absorbed.getId());
        priorLearn.setUserId(userId);
        priorLearn.setAction(MerchantLearningAudit.Action.LEARNED);
        priorLearn.setNewCategoryId(shoppingCategoryId);
        auditEntries.add(priorLearn);

        service.merge(userId, surviving.getId(), absorbed.getId(), actingAdminId);

        // The old LEARNED entry from before the merge must still exist, now attributed to the
        // surviving merchant -- NOT cascade-deleted along with the absorbed merchant row.
        assertThat(priorLearn.getMerchantId()).isEqualTo(surviving.getId());
        assertThat(auditEntries).contains(priorLearn);
    }

    @Test
    void merge_merchantNotOwnedByUser_throwsNotFound() {
        Merchant surviving = merchant("Amazon");
        Merchant notMine = merchant("Not Mine");
        UUID otherUser = UUID.randomUUID();
        notMine.setUserId(otherUser);

        assertThatThrownBy(() -> service.merge(userId, surviving.getId(), notMine.getId(), actingAdminId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");
    }

    // --- audit history ---

    @Test
    void auditHistory_resolvesCategoryIdsToHumanReadableNames() {
        Merchant m = merchant("Amazon");
        MerchantLearningAudit entry = new MerchantLearningAudit();
        entry.setMerchantId(m.getId());
        entry.setUserId(userId);
        entry.setAction(MerchantLearningAudit.Action.CORRECTED);
        entry.setPreviousCategoryId(shoppingCategoryId);
        entry.setNewCategoryId(electronicsCategoryId);
        auditEntries.add(entry);

        List<MerchantDto.AuditEntry> history = service.auditHistory(userId, m.getId());

        assertThat(history).hasSize(1);
        assertThat(history.get(0).action()).isEqualTo("CORRECTED");
        assertThat(history.get(0).previousCategory()).isEqualTo("Shopping");
        assertThat(history.get(0).newCategory()).isEqualTo("Electronics");
    }
}
