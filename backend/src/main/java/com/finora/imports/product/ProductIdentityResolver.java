package com.finora.imports.product;

import com.finora.entity.Account;
import com.finora.repository.AccountRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Decides whether a product found in a statement is one the user already holds.
 *
 * Scoped to the caller's own accounts, always. Identity is only ever meaningful within one person's
 * holdings -- two customers can hold the same deposit number at different banks, and a resolver
 * that searched globally would be a cross-tenant leak wearing a matching function's clothes.
 *
 * The output is deliberately a three-way decision rather than an {@code Optional<Account>}. A
 * caller that gets back "here is the account" cannot tell a certain match from a guess, and the
 * guess is the case that matters: merging two different deposits corrupts both, and splitting one
 * duplicates it in net worth. {@link Resolution#PROBABLE} exists so that case reaches a human.
 */
@Service
public class ProductIdentityResolver {

    private final AccountRepository accountRepository;

    public ProductIdentityResolver(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    public enum Resolution {
        /** No existing product matches -- this is genuinely new. */
        NEW,
        /** Certain: same institution, same product number. Safe to import into. */
        MATCHED,
        /** Plausible but unproven, or more than one candidate. Never auto-merged. */
        PROBABLE
    }

    /**
     * @param resolution what was concluded
     * @param account    the matched account for {@link Resolution#MATCHED}; the single best
     *                   candidate for {@link Resolution#PROBABLE}, which the review screen offers
     *                   rather than applies; null for {@link Resolution#NEW}
     * @param candidates every account that matched at all -- more than one is itself the reason a
     *                   match is only probable
     * @param reason     why, in words, for the review screen and the import report
     */
    public record ProductMatch(Resolution resolution, Account account, List<Account> candidates,
                               String reason) {

        public boolean mayImportWithoutAsking() { return resolution == Resolution.MATCHED; }
    }

    /** Finds the user's existing product matching {@code discovered}, if any. */
    public ProductMatch resolve(UUID userId, ProductIdentity discovered) {
        if (discovered == null) {
            return new ProductMatch(Resolution.NEW, null, List.of(), "no identity to match on");
        }

        List<Account> exact = new ArrayList<>();
        List<Account> probable = new ArrayList<>();

        for (Account account : accountRepository.findByUserId(userId)) {
            ProductIdentity stored = ProductIdentity.stored(
                    account.getBankId(), typeOf(account),
                    account.getProductIdentityHash(), account.getAccountNumberMasked());
            switch (discovered.matches(stored)) {
                case EXACT -> exact.add(account);
                case PROBABLE -> probable.add(account);
                case NONE -> { }
            }
        }

        // Two accounts sharing a strong key should be impossible, but "should be impossible" is not
        // a reason to pick one arbitrarily and import a statement into it. Duplicates can already
        // exist from before identity was recorded, which is precisely the mess this feature is
        // meant to stop growing -- so it asks rather than guessing which of them is right.
        if (exact.size() == 1) {
            return new ProductMatch(Resolution.MATCHED, exact.get(0), exact,
                    "same institution and product number as an existing " + typeOf(exact.get(0)));
        }
        if (exact.size() > 1) {
            return new ProductMatch(Resolution.PROBABLE, exact.get(0), exact,
                    exact.size() + " existing accounts share this product number -- "
                            + "they may be duplicates created before product identity was recorded");
        }
        if (probable.size() == 1) {
            return new ProductMatch(Resolution.PROBABLE, probable.get(0), probable,
                    "an existing " + typeOf(probable.get(0)) + " at the same institution ends in the "
                            + "same digits, but the statement gave no full number to confirm it");
        }
        if (probable.size() > 1) {
            return new ProductMatch(Resolution.PROBABLE, null, probable,
                    probable.size() + " existing accounts could be this product; "
                            + "the statement gave no full number to tell them apart");
        }
        return new ProductMatch(Resolution.NEW, null, List.of(), "no existing product matches");
    }

    /** An account's product type, falling back to its coarse account type for rows created before
     *  V49 backfilled the column (and for accounts a user typed in by hand). */
    private FinancialProductType typeOf(Account account) {
        if (account.getProductType() != null) {
            try {
                return FinancialProductType.valueOf(account.getProductType());
            } catch (IllegalArgumentException e) {
                // A value the enum no longer has -- a renamed constant, or a hand-edited row. Fall
                // through to the account type rather than failing an import over it.
            }
        }
        return Optional.ofNullable(account.getAccountType())
                .map(ProductIdentityResolver::coarseTypeOf)
                .orElse(FinancialProductType.UNKNOWN);
    }

    private static FinancialProductType coarseTypeOf(Account.Type type) {
        return switch (type) {
            case CREDIT_CARD -> FinancialProductType.CREDIT_CARD;
            case WALLET -> FinancialProductType.WALLET;
            case INVESTMENT -> FinancialProductType.UNKNOWN; // too coarse to claim FD vs RD vs fund
            case SAVINGS -> FinancialProductType.SAVINGS;
        };
    }
}
