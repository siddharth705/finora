package com.finora.imports.product;

import com.finora.entity.Account;
import com.finora.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductIdentityResolverTest {

    private final AccountRepository accountRepository = mock(AccountRepository.class);
    private final ProductIdentityResolver resolver = new ProductIdentityResolver(accountRepository);
    private final UUID userId = UUID.randomUUID();

    private Account account(String bankId, FinancialProductType type, String fullNumber, String masked) {
        Account a = new Account();
        a.setUserId(userId);
        a.setBankId(bankId);
        a.setProductType(type.name());
        a.setAccountType(type.accountType() == null ? Account.Type.SAVINGS : type.accountType());
        a.setAccountNumberMasked(masked);
        a.setProductIdentityHash(ProductIdentity.of(bankId, type, fullNumber, masked).strongKey());
        return a;
    }

    @BeforeEach
    void noAccountsByDefault() {
        when(accountRepository.findByUserId(any())).thenReturn(List.of());
    }

    @Test
    void anUnseenProductIsNew() {
        var found = resolver.resolve(userId,
                ProductIdentity.of("HDFC", FinancialProductType.FIXED_DEPOSIT, "40000000000004", "1234"));

        assertThat(found.resolution()).isEqualTo(ProductIdentityResolver.Resolution.NEW);
        assertThat(found.account()).isNull();
    }

    @Test
    void reimportingTheSameDepositMatchesTheExistingOne() {
        Account existing = account("HDFC", FinancialProductType.FIXED_DEPOSIT, "40000000000004", "1234");
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(existing));

        var found = resolver.resolve(userId,
                ProductIdentity.of("HDFC", FinancialProductType.FIXED_DEPOSIT, "40000000000004", "1234"));

        assertThat(found.resolution()).isEqualTo(ProductIdentityResolver.Resolution.MATCHED);
        assertThat(found.account()).isSameAs(existing);
        assertThat(found.mayImportWithoutAsking()).isTrue();
    }

    @Test
    void aMaskedOnlyMatchIsProbableAndNeverImportedIntoSilently() {
        // "Probably the same FD" means ask. This is the case that corrupts data if guessed.
        Account existing = account("HDFC", FinancialProductType.FIXED_DEPOSIT, null, "4521");
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(existing));

        var found = resolver.resolve(userId,
                ProductIdentity.of("HDFC", FinancialProductType.FIXED_DEPOSIT, null, "4521"));

        assertThat(found.resolution()).isEqualTo(ProductIdentityResolver.Resolution.PROBABLE);
        assertThat(found.mayImportWithoutAsking()).isFalse();
        assertThat(found.reason()).contains("no full number");
    }

    @Test
    void severalCandidatesAreNeverDisambiguatedByGuessing() {
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(
                account("HDFC", FinancialProductType.FIXED_DEPOSIT, null, "4521"),
                account("HDFC", FinancialProductType.FIXED_DEPOSIT, null, "4521")));

        var found = resolver.resolve(userId,
                ProductIdentity.of("HDFC", FinancialProductType.FIXED_DEPOSIT, null, "4521"));

        assertThat(found.resolution()).isEqualTo(ProductIdentityResolver.Resolution.PROBABLE);
        assertThat(found.account()).as("no single answer, so none is offered").isNull();
        assertThat(found.candidates()).hasSize(2);
    }

    @Test
    void preExistingDuplicatesAreSurfacedRatherThanPickedBetween() {
        // Duplicates can already exist from before identity was recorded -- exactly the mess this
        // feature stops growing. Importing into an arbitrary one of them would deepen it.
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(
                account("HDFC", FinancialProductType.FIXED_DEPOSIT, "40000000000004", "1234"),
                account("HDFC", FinancialProductType.FIXED_DEPOSIT, "40000000000004", "1234")));

        var found = resolver.resolve(userId,
                ProductIdentity.of("HDFC", FinancialProductType.FIXED_DEPOSIT, "40000000000004", "1234"));

        assertThat(found.resolution()).isEqualTo(ProductIdentityResolver.Resolution.PROBABLE);
        assertThat(found.reason()).contains("duplicates");
    }

    @Test
    void aDifferentProductAtTheSameBankIsNotAMatch() {
        Account savings = account("HDFC", FinancialProductType.SAVINGS, "50000000000005", "6000");
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(savings));

        var found = resolver.resolve(userId,
                ProductIdentity.of("HDFC", FinancialProductType.FIXED_DEPOSIT, "40000000000004", "1234"));

        assertThat(found.resolution()).isEqualTo(ProductIdentityResolver.Resolution.NEW);
    }

    /**
     * Closes the gap PdfMetadataExtractorTest's own PNB tests leave open: those prove extraction
     * succeeds in isolation, not that two INDEPENDENT extractions of PNB's "Statement of Account"
     * line -- one at account-creation time, one from a later statement -- actually resolve to the
     * SAME account rather than a second one. This is the real regression the production incident
     * exposed: PDF -> PdfMetadataExtractor -> ProductIdentity -> ProductIdentityResolver -> match,
     * exercised end-to-end rather than asserting on hand-typed identity values.
     */
    @Test
    void aSecondPnbStatementImport_extractsTheSameAccountNumberAndMatchesTheExistingAccount() {
        var extractor = new com.finora.imports.pdf.PdfMetadataExtractor();

        // First import: a January PNB statement is what created the account originally.
        var firstStatement = extractor.extract(List.of(
                "Statement of Account:98765432101234 For Period: 01/01/2026 to 31/01/2026")); // synthetic-ok
        Account existing = account("PNB", FinancialProductType.SAVINGS,
                firstStatement.accountNumberFullForHashingOnly(), firstStatement.accountNumberMasked());
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(existing));

        // Second import: a later PNB statement for the same real account, extracted independently
        // (a fresh extractor run against different statement text, not a reused value).
        var secondStatement = extractor.extract(List.of(
                "Statement of Account:98765432101234 For Period: 01/02/2026 to 28/02/2026")); // synthetic-ok
        var discovered = ProductIdentity.of("PNB", FinancialProductType.SAVINGS,
                secondStatement.accountNumberFullForHashingOnly(), secondStatement.accountNumberMasked());

        var found = resolver.resolve(userId, discovered);

        assertThat(found.resolution()).isEqualTo(ProductIdentityResolver.Resolution.MATCHED);
        assertThat(found.account()).isSameAs(existing);
        assertThat(found.mayImportWithoutAsking()).isTrue();
    }

    @Test
    void anAccountPredatingTheProductTypeColumnStillMatchesOnItsAccountType() {
        // V49 backfills product_type, but a hand-created account can still have it null. Falling
        // back to the coarse account type keeps identity working rather than treating every legacy
        // row as unmatched and duplicating it on the next import.
        Account legacy = account("HDFC", FinancialProductType.SAVINGS, "50000000000005", "6000");
        legacy.setProductType(null);
        when(accountRepository.findByUserId(userId)).thenReturn(List.of(legacy));

        var found = resolver.resolve(userId,
                ProductIdentity.of("HDFC", FinancialProductType.SAVINGS, "50000000000005", "6000"));

        assertThat(found.resolution()).isEqualTo(ProductIdentityResolver.Resolution.MATCHED);
    }
}
