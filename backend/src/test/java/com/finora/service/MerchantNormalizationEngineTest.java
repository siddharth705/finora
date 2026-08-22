package com.finora.service;

import com.finora.entity.Merchant;
import com.finora.entity.MerchantAlias;
import com.finora.repository.MerchantAliasRepository;
import com.finora.repository.MerchantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * MerchantNormalizationEngine had no test of its own; it was only exercised indirectly through
 * CategorizationServiceTest with a mocked engine.
 *
 * <p>Two things are pinned here, and the correctness half matters more than the performance half.
 * Merchant identity feeds categorisation learning, so if this resolves two spellings of the same
 * merchant to two different rows, the engine learns the user's correction against one of them and
 * silently fails to apply it to the other, forever. That is the regression any change to this
 * class risks, so it is written down before the class is touched.
 *
 * <p>The fakes below are hand-rolled rather than Mockito stubs because the behaviour under test is
 * stateful: {@code resolve()} creates merchants as it goes, and a later call has to see a merchant
 * an earlier call created. A stub returning a fixed list cannot express that, and would happily
 * pass an implementation that is broken in exactly the way this class is easiest to break.
 */
class MerchantNormalizationEngineTest {

    private final UUID userId = UUID.randomUUID();

    /** Counts the per-row merchant scan, which is the operation this class's cost is measured in. */
    private int merchantScanCalls;

    /** Counts the entity load the projection path added: one findById per token match. */
    private int findByIdCalls;

    private MerchantRepository merchantRepository;
    private MerchantAliasRepository merchantAliasRepository;
    private MerchantNormalizationEngine engine;

    private final List<Merchant> merchants = new ArrayList<>();
    private final List<MerchantAlias> aliases = new ArrayList<>();

    @BeforeEach
    void setUp() {
        merchants.clear();
        aliases.clear();
        merchantScanCalls = 0;
        findByIdCalls = 0;

        merchantRepository = mock(MerchantRepository.class);
        merchantAliasRepository = mock(MerchantAliasRepository.class);

        when(merchantRepository.findByUserId(any())).thenAnswer(inv -> {
            merchantScanCalls++;
            return List.copyOf(merchants);
        });
        // findByIdAndUserId, not findById: the engine no longer issues a bare findById, because
        // MerchantRepository's own comment states the rule as "never a bare findById" and the
        // alias-hit path was the one undocumented exception to it. Same lookup, same counter --
        // the id still comes from a user-scoped alias row, so this was never a scoping hole, only
        // a rule with a hole in it.
        when(merchantRepository.findByIdAndUserId(any(), any())).thenAnswer(inv -> {
            findByIdCalls++;
            UUID id = inv.getArgument(0);
            UUID scopedUserId = inv.getArgument(1);
            return merchants.stream()
                    .filter(m -> id.equals(m.getId()) && scopedUserId.equals(m.getUserId()))
                    .findFirst();
        });
        when(merchantRepository.save(any(Merchant.class))).thenAnswer(inv -> {
            Merchant m = inv.getArgument(0);
            // Merchant.id is JPA-generated with no setter; the real save() assigns it on persist.
            ReflectionTestUtils.setField(m, "id", UUID.randomUUID());
            merchants.add(m);
            return m;
        });
        when(merchantAliasRepository.findByUserIdAndNormalizedAlias(any(), anyString()))
                .thenAnswer(inv -> {
                    String alias = inv.getArgument(1);
                    return aliases.stream().filter(a -> alias.equals(a.getNormalizedAlias())).findFirst();
                });
        // insertIfAbsent, not save/saveAndFlush: addAlias is now an atomic INSERT ... ON CONFLICT
        // DO NOTHING -- see its own doc comment for the poisoned-transaction race that a
        // saveAndFlush()+catch(DataIntegrityViolationException) recovery path could not survive.
        // A mock can't reproduce a real unique-constraint collision, so this fake just mirrors the
        // same idempotency the real query gives: 1 (inserted) the first time, 0 (no-op) after.
        when(merchantAliasRepository.insertIfAbsent(any(), any(), anyString())).thenAnswer(inv -> {
            UUID merchantId = inv.getArgument(0);
            UUID aliasUserId = inv.getArgument(1);
            String normalizedAlias = inv.getArgument(2);
            boolean alreadyExists = aliases.stream().anyMatch(a -> normalizedAlias.equals(a.getNormalizedAlias()));
            if (alreadyExists) return 0;
            MerchantAlias a = new MerchantAlias();
            a.setMerchantId(merchantId);
            a.setUserId(aliasUserId);
            a.setNormalizedAlias(normalizedAlias);
            aliases.add(a);
            return 1;
        });

        engine = new MerchantNormalizationEngine(merchantRepository, merchantAliasRepository);
    }

    // ---- correctness ----

    @Test
    @DisplayName("the same description resolves to the same merchant every time")
    void identicalDescriptionsShareAMerchant() {
        Merchant first = engine.resolve(userId, "SWIGGY BANGALORE");
        Merchant second = engine.resolve(userId, "SWIGGY BANGALORE");

        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(merchants).hasSize(1);
    }

    /**
     * The one that guards the obvious-but-wrong optimisation. Snapshotting the merchant list once
     * before a loop makes row 3 unable to see the merchant row 1 created, so each row creates its
     * own -- three "Swiggy" merchants instead of one, splitting the user's spend across them and
     * splitting what the learning engine is taught.
     */
    @Test
    @DisplayName("different spellings sharing a first token collapse onto ONE new merchant")
    void differentSpellingsOfANewMerchantCollapseOntoOne() {
        Merchant a = engine.resolve(userId, "SWIGGY BANGALORE");
        Merchant b = engine.resolve(userId, "SWIGGY ORDER 4471");
        Merchant c = engine.resolve(userId, "SWIGGY INSTAMART");

        assertThat(merchants)
                .as("all three descriptions are the same merchant; creating one row per spelling "
                        + "splits the user's spend and splits what the learning engine is taught")
                .hasSize(1);
        assertThat(b.getId()).isEqualTo(a.getId());
        assertThat(c.getId()).isEqualTo(a.getId());
    }

    @Test
    @DisplayName("genuinely different merchants stay separate")
    void distinctMerchantsAreNotCollapsed() {
        engine.resolve(userId, "SWIGGY BANGALORE");
        engine.resolve(userId, "UBER TRIP 8891");
        engine.resolve(userId, "AMAZON RETAIL");

        assertThat(merchants).hasSize(3);
    }

    // ---- Bug 03: payment-rail prefixes must not become the grouping key ----

    /**
     * The reported Critical, in the shape a real Indian bank statement produces it.
     *
     * <p>Grouping used to key on the first token longer than two characters. Every rail name is
     * longer than two characters, so the key for every one of these rows was {@code upi}, and all
     * four aliased onto whichever payee happened to be resolved first. UPI is the dominant rail in
     * this product's market, so this was close to "every transaction is one merchant" — and a
     * merchant's confirmation counts are what ConfidenceEngine.topCategory reads to pick the
     * auto-applied category, so the damage lands in the learning engine, not just the merchant list.
     */
    @Test
    @DisplayName("BUG 03: UPI payees are four merchants, not one")
    void upiPayeesDoNotCollapseOntoOneMerchant() {
        engine.resolve(userId, "UPI/9182736/SWIGGY");
        engine.resolve(userId, "UPI/5647382/ZOMATO");
        engine.resolve(userId, "UPI/1122334/AMAZON");
        engine.resolve(userId, "UPI/9988776/UBER");

        assertThat(merchants)
                .as("the rail names how the money moved, not who it moved to -- keying on it "
                        + "makes every UPI payee the same merchant")
                .hasSize(4);
    }

    @Test
    @DisplayName("every rail behaves the same way, not just UPI")
    void otherRailsDoNotCollapseEither() {
        // Bank prefix kept, branch code masked -- the Synthetic Fixture Policy's own placeholder
        // shape. The prefix is the part that carries meaning for a layout; the branch code is not.
        engine.resolve(userId, "NEFT-HDFC0XXXXXX-ACME LTD");
        engine.resolve(userId, "IMPS/P2A/512345/BIGBASKET");
        engine.resolve(userId, "POS 4471 DECATHLON");
        engine.resolve(userId, "ATM WDL 8891 AXIS");

        assertThat(merchants).hasSize(4);
    }

    /**
     * The half of this fix that is easy to miss. Skipping the rail alone would have moved the key
     * onto the REFERENCE — "upi 9182736 swiggy" would group on {@code 9182736}, which is unique per
     * transaction — turning total over-grouping into total under-grouping. Both sides of the
     * comparison are reduced by CategoryRules.extractMerchant, which strips reference tokens, so
     * the same payee still collapses onto one merchant across differing references.
     */
    @Test
    @DisplayName("one payee across differing references is still ONE merchant")
    void sameUpiPayeeAcrossDifferentReferencesStillCollapses() {
        Merchant a = engine.resolve(userId, "UPI/9182736/SWIGGY");
        Merchant b = engine.resolve(userId, "UPI/5647382/SWIGGY");
        Merchant c = engine.resolve(userId, "SWIGGY ORDER 4471");

        assertThat(merchants)
                .as("stripping the rail must not promote the per-transaction reference into the key")
                .hasSize(1);
        assertThat(b.getId()).isEqualTo(a.getId());
        assertThat(c.getId()).isEqualTo(a.getId());
    }

    @Test
    @DisplayName("a description that is only rail and reference gets its own merchant, never a match")
    void railOnlyDescriptionDoesNotMatchAnything() {
        engine.resolve(userId, "SWIGGY BANGALORE");
        engine.resolve(userId, "UPI 12345");

        assertThat(merchants)
                .as("with no counterparty to group by, creating a separate merchant is the "
                        + "recoverable failure; matching an unrelated one is not")
                .hasSize(2);
    }

    @Test
    @DisplayName("staging previews the same merchant the confirm would create")
    void readOnlyResolutionAgreesWithResolve() {
        Merchant confirmed = engine.resolve(userId, "UPI/9182736/SWIGGY");

        // A different reference for the same payee, as a later row would carry.
        assertThat(engine.resolveReadOnly(userId, "UPI/5647382/SWIGGY"))
                .as("resolve() and resolveReadOnly() must reduce the description identically, or a "
                        + "staged preview shows a merchant the confirm will not pick")
                .contains(confirmed);
        assertThat(engine.resolveReadOnly(userId, "UPI/1122334/ZOMATO")).isEmpty();
    }

    @Test
    @DisplayName("an alias recorded earlier short-circuits to its merchant")
    void knownAliasSkipsTheScan() {
        Merchant created = engine.resolve(userId, "SWIGGY BANGALORE");
        int afterFirst = merchantScanCalls;

        Merchant again = engine.resolve(userId, "SWIGGY BANGALORE");

        assertThat(again.getId()).isEqualTo(created.getId());
        assertThat(merchantScanCalls)
                .as("an exact alias hit must not fall through to the full-table scan")
                .isEqualTo(afterFirst);
    }

    /**
     * Bug 56. An alias row can outlive its target merchant -- e.g. MerchantReviewService.discard()
     * removes a merchant with no attached transactions but, unlike MerchantService.merge(), never
     * repoints or deletes its aliases. Before this fix, resolve() found the dangling alias, missed
     * the merchant lookup, and fell into createMerchantAndAlias -- whose addAlias() is an
     * INSERT ... ON CONFLICT DO NOTHING that silently no-ops because the alias already exists (just
     * pointing at the dead merchant). Every single call for that description created and returned a
     * BRAND NEW merchant, none of which the alias table ever pointed at -- a fresh duplicate every
     * time, forever.
     */
    @Test
    @DisplayName("Bug 56: a dangling alias is repointed, not left to spawn a new merchant every call")
    void danglingAliasIsRepointedRatherThanSpawningANewMerchantForever() {
        Merchant original = engine.resolve(userId, "SWIGGY BANGALORE");
        assertThat(aliases).hasSize(1);

        // Simulate the merchant having been deleted (e.g. discard()) without touching its alias --
        // the alias is now dangling, pointing at an id no longer in `merchants`.
        merchants.removeIf(m -> m.getId().equals(original.getId()));

        Merchant repaired = engine.resolve(userId, "SWIGGY BANGALORE");
        Merchant repairedAgain = engine.resolve(userId, "SWIGGY BANGALORE");

        assertThat(repaired.getId())
                .as("a replacement merchant is created for the now-missing one")
                .isNotEqualTo(original.getId());
        assertThat(repairedAgain.getId())
                .as("the SECOND call must find the repointed alias, not spawn yet another merchant")
                .isEqualTo(repaired.getId());
        assertThat(aliases)
                .as("the dangling row is repointed in place, never duplicated")
                .hasSize(1);
        assertThat(aliases.get(0).getMerchantId()).isEqualTo(repaired.getId());
    }

    // ---- cost ----

    /**
     * Characterises the cost this class is currently paying, so any change to it is a measured
     * change rather than an asserted one.
     *
     * <p>Every description that is not already a known alias triggers
     * {@code merchantRepository.findByUserId(userId)} -- a full load of every merchant the user
     * has, as managed entities, filtered in Java. On a first import that is the common case, not the rare one: the aliases
     * do not exist yet, by definition.
     */
    @Test
    @DisplayName("cost: every previously-unseen description triggers a full merchant load")
    void everyUnseenDescriptionCostsAFullLoad() {
        int distinctMerchants = 40;
        for (int i = 0; i < distinctMerchants; i++) {
            engine.resolve(userId, "MERCHANT" + i + " STORE " + i);
        }

        assertThat(merchants).hasSize(distinctMerchants);
        assertThat(merchantScanCalls)
                .as("one scan per unseen description")
                .isEqualTo(distinctMerchants);
        System.out.println("MEASURE 40-distinct scans=" + merchantScanCalls + " findById=" + findByIdCalls);
    }

    /**
     * The shape that actually matters, and the reason the alias cache does not save the import:
     * real bank descriptions carry a per-transaction reference ("SWIGGY ORDER 4471"), so almost
     * every row is a distinct alias even when it is the same handful of merchants over and over.
     *
     * <p><b>This measures the NO-TRANSACTION path</b>, where the memo is deliberately skipped —
     * see realisticStatementInsideATransactionLoadsOnce for the same statement inside a
     * transaction, which is what every real caller does and what Bug 35 was about.
     */
    @Test
    @DisplayName("cost: a realistic statement loads the merchant table once per ROW, not per merchant")
    void realisticStatementLoadsPerRow() {
        int rows = 500;
        int distinctMerchants = 50;
        for (int i = 0; i < rows; i++) {
            // Same 50 merchants, but each row's reference number makes it a new alias.
            engine.resolve(userId, "MERCHANT" + (i % distinctMerchants) + " REF " + i);
        }

        assertThat(merchants)
                .as("the token match still collapses them correctly")
                .hasSize(distinctMerchants);
        assertThat(merchantScanCalls)
                .as("one merchant scan per row of the statement")
                .isEqualTo(rows);
        System.out.println("MEASURE realistic-500row scans=" + merchantScanCalls
                + " findById=" + findByIdCalls + " merchants=" + merchants.size());
    }

    /**
     * The fix, measured: inside a transaction, the whole statement costs ONE load.
     *
     * <p>The two measurements above are of the path with no active transaction, which is why they
     * still show one scan per row — the memo is deliberately skipped there, so nothing depends on
     * a caller being transactional. Every real caller IS: {@code resolve} is {@code @Transactional}
     * and the import runs inside {@code ImportService.confirm}'s transaction. This drives that path
     * by starting a synchronization the way Spring's transaction manager does.
     *
     * <p>500 rows, 50 distinct merchants: 500 full merchant-table loads become 1.
     */
    @Test
    @DisplayName("cost: inside a transaction the same statement loads the merchant table once")
    void realisticStatementInsideATransactionLoadsOnce() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            int rows = 500;
            int distinctMerchants = 50;
            for (int i = 0; i < rows; i++) {
                engine.resolve(userId, "MERCHANT" + (i % distinctMerchants) + " REF " + i);
            }

            assertThat(merchants)
                    .as("collapsing is unchanged -- the memo holds the same rows the query returned")
                    .hasSize(distinctMerchants);
            assertThat(merchantScanCalls)
                    .as("one load for the whole statement, not one per row")
                    .isEqualTo(1);
            System.out.println("MEASURE in-transaction-500row scans=" + merchantScanCalls
                    + " merchants=" + merchants.size());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /**
     * The correctness property the memo must not break, and the reason a plain snapshot was
     * rejected: {@code resolve} CREATES merchants as it goes, so a cache populated once at the
     * start would not contain the merchant an earlier row just created. Three spellings of a new
     * merchant would then become three merchants, splitting the user's spend and splitting what
     * the learning engine is taught.
     */
    @Test
    @DisplayName("a merchant created mid-transaction is visible to later rows in the same transaction")
    void aMerchantCreatedMidTransactionIsSeenByLaterRows() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            engine.resolve(userId, "SWIGGY BANGALORE");
            engine.resolve(userId, "SWIGGY ORDER 4471");
            engine.resolve(userId, "SWIGGY*BLR 9982");

            assertThat(merchants)
                    .as("all three collapse onto the merchant the first row created")
                    .hasSize(1);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** The same statement re-imported costs nothing extra: every alias is known by then. */
    @Test
    @DisplayName("cost: a re-import of the same descriptions triggers no further loads")
    void reimportCostsNoFurtherLoads() {
        List<String> statement = List.of("SWIGGY BANGALORE", "UBER TRIP 8891", "AMAZON RETAIL");
        statement.forEach(d -> engine.resolve(userId, d));
        int afterFirstImport = merchantScanCalls;

        statement.forEach(d -> engine.resolve(userId, d));

        assertThat(merchantScanCalls).isEqualTo(afterFirstImport);
    }
}
