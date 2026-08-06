package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Merchant;
import com.finora.entity.User;
import com.finora.repository.MerchantAliasRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * A misparsed narration must not become an HTTP 500.
 *
 * <p>Found by running eleven real statements through the engine. One credit-card statement
 * produced a 400-character "merchant" — a single transaction narration that had absorbed a page of
 * cheque instructions ("...please write your name telephone no on the reverse of the cheque...").
 * That exceeded {@code merchant_aliases.normalized_alias VARCHAR(255)}, the JDBC batch aborted, the
 * transaction was marked rollback-only, and the import died later with
 * {@code UnexpectedRollbackException}. The user saw a 500 for a document the parser had merely
 * misread.
 *
 * <p>Two defects, and the second was worse than the first. The catch around the insert assumed
 * every {@code DataIntegrityViolationException} was the concurrent-insert race it had been written
 * for, so it logged "was created concurrently" at DEBUG and carried on — a diagnosis that was
 * untrue, at a level nobody reads, for an error that had already poisoned the transaction.
 *
 * <p>Runs against a real database on purpose. A mocked repository cannot exceed a column width, so
 * every unit test here would pass against the broken code.
 */
class MerchantOversizedDescriptionIT extends AbstractIntegrationTest {

    @Autowired private MerchantNormalizationEngine engine;
    @Autowired private MerchantAliasRepository merchantAliasRepository;
    @Autowired private UserRepository userRepository;

    /**
     * Entirely synthetic, and only the SHAPE is copied from the real failure: a short plausible
     * narration with a page of statement boilerplate appended, totalling well over 255 characters.
     *
     * <p>The first draft of this fixture pasted the real narration, and
     * {@code check-fixture-hygiene.sh} blocked the commit over a payee name and phone number
     * carried across with it. Nothing about this test needs a real value — the bug is triggered by
     * LENGTH, and the assertion below states that explicitly.
     */
    private static final String OVERSIZED_NARRATION =
            "upi example payee axis0000000 ptm "
            + "your cheque should be payable to the bank card no xxxx xxxx please write your name "
            + "telephone no on the reverse of the cheque dear customer pay your credit card bill "
            + "from any bank account by registering for ecs at any branch visit the bank website to "
            + "download the form terms and conditions apply please retain this statement for your "
            + "records and contact customer care for any discrepancy within thirty days";

    private UUID newUser() {
        User user = new User();
        user.setEmail("oversized-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("x");
        user.setFullName("Oversized Narration");
        return userRepository.save(user).getId();
    }

    @Test
    void anOversizedNarrationResolvesInsteadOfPoisoningTheTransaction() {
        UUID userId = newUser();
        assertThat(OVERSIZED_NARRATION.length())
                .as("the fixture has to actually exceed the column, or it tests nothing")
                .isGreaterThan(255);

        Merchant merchant = engine.resolve(userId, OVERSIZED_NARRATION);

        // Non-null matters as much as not-throwing: every caller of resolve() dereferences the
        // result immediately, so returning null would have traded the rollback for an NPE -- the
        // same 500 by a different route.
        assertThat(merchant).isNotNull();
        assertThat(merchant.getCanonicalName()).isNotNull();
        assertThat(merchant.getCanonicalName().length()).isLessThanOrEqualTo(255);
    }

    @Test
    void theTransactionStaysUsableAfterwards() {
        UUID userId = newUser();

        engine.resolve(userId, OVERSIZED_NARRATION);

        // The actual bug was never the failed insert -- it was everything AFTER it failing too,
        // because the transaction was already rollback-only. A subsequent, entirely ordinary
        // resolve is the witness: it succeeds only if nothing poisoned the connection.
        assertThatCode(() -> engine.resolve(userId, "SWIGGY ORDER 4471"))
                .doesNotThrowAnyException();

        assertThat(merchantAliasRepository.findByUserIdAndNormalizedAlias(
                userId, com.finora.util.CategoryRules.normalize("SWIGGY ORDER 4471")))
                .as("a later, well-formed row must still import")
                .isPresent();
    }

    @Test
    void theSameMisparsedNarrationMapsToTheSameMerchantTwice() {
        UUID userId = newUser();

        Merchant first = engine.resolve(userId, OVERSIZED_NARRATION);
        Merchant second = engine.resolve(userId, OVERSIZED_NARRATION);

        // Truncation has to be deterministic. If it were not, a re-import of the same statement
        // would multiply merchant rows instead of matching the existing one -- which is the
        // failure mode that makes truncation worse than useless.
        assertThat(second.getId()).isEqualTo(first.getId());
    }

    @Test
    void aNarrationJustUnderTheLimitIsStoredWhole() {
        UUID userId = newUser();
        String justUnder = "a".repeat(200);

        engine.resolve(userId, justUnder);

        // Guards against a fix that truncates everything. Only the oversized case should change.
        assertThat(merchantAliasRepository.findByUserIdAndNormalizedAlias(
                userId, com.finora.util.CategoryRules.normalize(justUnder)))
                .isPresent();
    }
}
