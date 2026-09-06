package com.finora.service;

import com.finora.entity.Merchant;
import com.finora.entity.Transaction;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.TransactionRepository;
import com.finora.util.CounterpartyIdentity;
import com.finora.util.CounterpartyType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Groups a user's already-persisted needs-review transactions by merchant or by counterparty, so
 * the Ledger can offer "5 Swiggy transactions found" instead of 5 separate one-by-one corrections.
 * Reuses the existing needs-review query and re-groups in Java rather than a GROUP BY query, the
 * same choice AdminPlatformAnalyticsService already made for the identical reason: {@code
 * Transaction} has no JPA association to {@code Merchant}, only a plain UUID column, so there's no
 * JPQL join path to the name -- and {@code counterparty_key} is a plain column with no entity of
 * its own to join at all.
 *
 * <p>Groups of exactly one transaction are deliberately excluded from BOTH groupings — those stay
 * in the existing AskOnceCard one-by-one flow (see
 * docs/proposals/transaction-intelligence-engine-phase0-audit.md), so nothing changes for a user
 * with no repeat backlog.
 *
 * <h2>Why two groupings instead of one merged pass</h2>
 *
 * <p>{@link #groupNeedsReviewByCounterparty} skips any row that already has a merchant match
 * ({@code t.getMerchantId() != null}), so the two groupings partition the backlog rather than
 * surfacing the same row twice under two different group headers. This is deliberate, not an
 * oversight: a merchant match already implies a strong category guess (a known "Swiggy" merchant
 * all but says "Dining"), while a counterparty only says WHO, not WHAT FOR -- so counterparty
 * grouping earns its keep specifically on the rows merchant grouping cannot reach, the long tail
 * {@code MerchantNormalizationEngine}'s first-token heuristic misses.
 */
@Service
public class TransactionGroupingService {

    private static final int MIN_GROUP_SIZE = 2;

    private final TransactionRepository transactionRepository;
    private final MerchantRepository merchantRepository;
    private final AccountRepository accountRepository;

    public TransactionGroupingService(TransactionRepository transactionRepository,
                                       MerchantRepository merchantRepository,
                                       AccountRepository accountRepository) {
        this.transactionRepository = transactionRepository;
        this.merchantRepository = merchantRepository;
        this.accountRepository = accountRepository;
    }

    /**
     * The account-scoped needs-review candidate set both groupings start from. Extracted once both
     * groupings needed the identical query -- see {@link #groupNeedsReviewByMerchant}'s own doc for
     * why the deleted-account scoping below has to happen here rather than being assumed.
     */
    private List<Transaction> needsReviewCandidates(UUID userId) {
        // Deleted-account leak (see DashboardService.summarize for the original fix): a deleted
        // account's transactions deliberately keep deleted_at unset, so the unscoped finder would
        // keep surfacing them in this grouping forever, not just during
        // StatementImportService's 7-day grace window. This is a separate call site from
        // TransactionService.needsReview -- not called through it -- so it needs its own scoping.
        List<UUID> liveAccountIds = accountRepository.findByUserId(userId).stream()
                .map(com.finora.entity.Account::getId).toList();
        return liveAccountIds.isEmpty() ? List.of()
                : transactionRepository.findByUserIdAndNeedsCategoryReviewTrueAndAccountIdInOrderByTxnDateDesc(
                        userId, liveAccountIds);
    }

    /**
     * Just enough of each grouped transaction to preview it before committing to a bulk category --
     * date/description/amount/type, the same fields the Ledger's own table row already shows.
     * Deliberately not the full {@code TransactionDto}: this list can run to dozens of rows inside
     * one card, and everything else on that DTO (tags, notes, reconciliation status, ...) has no
     * use here -- the only decision this card makes is "does this category apply to all of these",
     * which these four fields are already enough to judge.
     */
    public record TransactionSummary(UUID id, java.time.LocalDate date, String description,
                                       java.math.BigDecimal amount, String type) {
        static TransactionSummary from(Transaction t) {
            return new TransactionSummary(t.getId(), t.getTxnDate(), t.getDescription(), t.getAmount(),
                    t.getTxnType().name());
        }
    }

    /**
     * {@code transactionIds} stays the flat id list {@code apply}'s bulk-recategorize call has
     * always taken -- {@code transactions} is purely additive, for the "preview before applying"
     * expansion (Ledger's MerchantGroupReviewCard). The two are never allowed to disagree: both are
     * derived from the same {@code List<Transaction>} per merchant in one pass below, not built
     * from two separate queries that could drift apart.
     */
    public record MerchantGroup(UUID merchantId, String merchantName, List<UUID> transactionIds,
                                 List<TransactionSummary> transactions) {}

    public List<MerchantGroup> groupNeedsReviewByMerchant(UUID userId) {
        List<Transaction> candidates = needsReviewCandidates(userId);

        Map<UUID, List<Transaction>> transactionsByMerchant = new LinkedHashMap<>();
        for (Transaction t : candidates) {
            if (t.getMerchantId() == null) continue;
            // A transaction already flagged as a duplicate shouldn't inflate the group's count or be
            // bulk-categorized alongside its canonical counterpart -- it's resolved separately via
            // the duplicate-review flow, not this one.
            if (t.getReconciliationStatus() == Transaction.ReconciliationStatus.DUPLICATE) continue;
            transactionsByMerchant.computeIfAbsent(t.getMerchantId(), k -> new ArrayList<>()).add(t);
        }

        // One query for every merchant name this user could possibly need here, rather than one
        // per distinct merchant with a review backlog -- the same batch-then-look-up-in-memory
        // pattern MerchantNormalizationEngine.indexFor uses for the identical reason. A dangling
        // merchant id (its Merchant row is gone, e.g. discarded via MerchantReviewService) simply
        // has no entry here, matching findByIdAndUserId's empty-Optional "skip it" behavior.
        Map<UUID, String> nameById = new java.util.HashMap<>();
        for (Merchant merchant : merchantRepository.findByUserId(userId)) {
            nameById.put(merchant.getId(), merchant.getCanonicalName());
        }

        List<MerchantGroup> groups = new ArrayList<>();
        for (var entry : transactionsByMerchant.entrySet()) {
            List<Transaction> txns = entry.getValue();
            if (txns.size() < MIN_GROUP_SIZE) continue;
            String merchantName = nameById.get(entry.getKey());
            if (merchantName == null) continue;
            groups.add(new MerchantGroup(entry.getKey(), merchantName,
                    txns.stream().map(Transaction::getId).toList(),
                    txns.stream().map(TransactionSummary::from).toList()));
        }

        groups.sort((a, b) -> b.transactionIds().size() - a.transactionIds().size());
        return groups;
    }

    /**
     * One group's answer to "who was this" plus the material a reviewer needs to trust it.
     *
     * @param counterpartyKey the raw grouping key ({@code vpa:...} or {@code name:...}), not shown
     *                        to a user directly -- see {@link #label}
     * @param counterpartyType PERSON or BUSINESS -- {@link #groupNeedsReviewByCounterparty} never
     *                         produces any other value, but the field stays the real enum rather
     *                         than a boolean so a client can render the same "sent to"/"paid" copy
     *                         {@code counterpartyLabel()} already uses elsewhere. Taken from the
     *                         group's most recent transaction, which is USUALLY every transaction's
     *                         type -- but not provably always: measured on the real corpus, 2 of 621
     *                         distinct PERSON/BUSINESS keys (0.3%) carry both types across their own
     *                         occurrences (e.g. one narration shape for a real business classifying
     *                         as PERSON). The grouping itself is unaffected -- {@code
     *                         counterpartyKey} is the only thing rows are grouped by, so a bulk
     *                         apply still targets the correct set of same-payee rows -- only the
     *                         displayed badge can show either of two plausible labels for that rare
     *                         case. Accepted rather than engineered around: a third "mixed" badge
     *                         state, or splitting a group by (key, type), is its own product
     *                         decision for a 0.3% edge case, not a default worth making silently.
     * @param identityIsStrong whether {@code counterpartyKey} came from a UPI VPA ({@link
     *                         CounterpartyIdentity#isStrong}) rather than a guessed name fragment --
     *                         {@code CounterpartyIdentity}'s own doc is explicit that a {@code
     *                         name:} key "must never be presented to a user as an identity", so a
     *                         client MUST read this flag before implying the group is a confirmed
     *                         match rather than a probable one
     * @param label a human-readable stand-in for "who", since neither key shape is one -- see
     *              {@link #groupNeedsReviewByCounterparty} for why it is the narration text of the
     *              group's own most recent transaction rather than an invented "resolved name"
     * @param totalValue sum of {@code abs(amount)} across the group -- the sort key that
     *                   distinguishes this from {@link #groupNeedsReviewByMerchant}, which sorts by
     *                   row count. Measured on the real corpus: the top 3 counterparties in a
     *                   statement carry 51.9% of its unresolved value, a concentration row-count
     *                   sorting does not reliably surface
     */
    public record CounterpartyGroup(String counterpartyKey, CounterpartyType counterpartyType,
                                     boolean identityIsStrong, String label, BigDecimal totalValue,
                                     List<UUID> transactionIds, List<TransactionSummary> transactions) {}

    /**
     * Groups needs-review transactions by counterparty, for the rows {@link
     * #groupNeedsReviewByMerchant} cannot reach at all -- see this class's own "Why two groupings"
     * doc for why a merchant-matched row is skipped here rather than shown under both headers.
     *
     * <p>Scoped to PERSON and BUSINESS only. FINANCIAL_INSTITUTION/GOVERNMENT rows (bank charges,
     * mandate debits, interest, tax) are a materially different review prompt -- "who did I pay
     * this annual card fee to" is not a useful question -- and are excluded rather than shown
     * alongside the rows a human genuinely needs to weigh in on.
     */
    public List<CounterpartyGroup> groupNeedsReviewByCounterparty(UUID userId) {
        List<Transaction> candidates = needsReviewCandidates(userId);

        Map<String, List<Transaction>> transactionsByCounterparty = new LinkedHashMap<>();
        for (Transaction t : candidates) {
            // The partition with groupNeedsReviewByMerchant -- see this class's own "Why two
            // groupings" doc.
            if (t.getMerchantId() != null) continue;
            if (t.getReconciliationStatus() == Transaction.ReconciliationStatus.DUPLICATE) continue;
            CounterpartyType type = t.getCounterpartyType();
            if (type != CounterpartyType.PERSON && type != CounterpartyType.BUSINESS) continue;
            String key = t.getCounterpartyKey();
            if (key == null) continue;
            transactionsByCounterparty.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }

        List<CounterpartyGroup> groups = new ArrayList<>();
        for (var entry : transactionsByCounterparty.entrySet()) {
            List<Transaction> txns = entry.getValue();
            if (txns.size() < MIN_GROUP_SIZE) continue;

            BigDecimal totalValue = txns.stream()
                    .map(t -> t.getAmount().abs())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            // needsReviewCandidates is already ORDER BY txnDate DESC, so txns.get(0) is this
            // group's most recent transaction -- its narration is the label. Not an invented
            // "resolved counterparty name": neither key shape (a UPI handle fragment, or a guessed
            // name token) is fit to show as one, so this shows what a person would see if they
            // opened that row themselves, same fallback the Ledger table row itself already uses.
            //
            // The getMerchant() fallback can never actually fire here: reaching this loop at all
            // required a non-null counterparty key, and CounterpartyIdentity.keyOf (like
            // CounterpartyClassifier.classify) returns nothing for a null/blank description -- so a
            // row with no description could never have carried a key in the first place. Kept
            // anyway, matching the Ledger table row's own fallback, rather than asserting an
            // invariant that isn't this method's to enforce.
            Transaction mostRecent = txns.get(0);
            String label = mostRecent.getDescription() != null && !mostRecent.getDescription().isBlank()
                    ? mostRecent.getDescription() : mostRecent.getMerchant();

            groups.add(new CounterpartyGroup(entry.getKey(), mostRecent.getCounterpartyType(),
                    CounterpartyIdentity.isStrong(entry.getKey()), label, totalValue,
                    txns.stream().map(Transaction::getId).toList(),
                    txns.stream().map(TransactionSummary::from).toList()));
        }

        groups.sort(Comparator.comparing(CounterpartyGroup::totalValue).reversed());
        return groups;
    }
}
