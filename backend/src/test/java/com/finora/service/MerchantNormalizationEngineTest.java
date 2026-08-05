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
        // saveAndFlush, not save: addAlias forces the unique-constraint check to happen where it
        // can be caught, rather than at commit -- see its own doc comment for the concurrent-import
        // race that made a duplicate alias roll back an entire import.
        when(merchantAliasRepository.saveAndFlush(any(MerchantAlias.class))).thenAnswer(inv -> {
            MerchantAlias a = inv.getArgument(0);
            aliases.add(a);
            return a;
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
     * Every one of those rows falls through to a full load of the user's merchant table.
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
