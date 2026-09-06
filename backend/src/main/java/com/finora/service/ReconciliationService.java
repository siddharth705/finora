package com.finora.service;

import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.entity.TransactionRelationship;
import com.finora.repository.AccountRepository;
import com.finora.repository.TransactionRepository;
import com.finora.util.CategoryRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ReconciliationService {

    // The thresholds these passes decide by now live in ReconciliationPolicy -- they are business
    // rules rather than implementation detail, and there was nowhere to read them together. Values
    // are unchanged; see that class for each one's reasoning and for why none of them is
    // configurable. Imported statically so the matching code below still reads as prose.
    private static final long DEFAULT_TRANSFER_DAY_WINDOW = ReconciliationPolicy.DEFAULT_TRANSFER_DAY_WINDOW;
    private static final long OWN_ACCOUNT_MATCH_DAY_WINDOW = ReconciliationPolicy.OWN_ACCOUNT_MATCH_DAY_WINDOW;
    private static final long REFUND_WINDOW_DAYS = ReconciliationPolicy.REFUND_WINDOW_DAYS;

    // Keyword signal for "this INCOME row is a refund," independent of merchant matching -- see
    // the refund pass below. Deliberately not folded into CategoryRules (util package): that
    // table drives the CATEGORY suggestion ("Fees/Interest", "Transfer", ...), this drives
    // RECONCILIATION status, a different concern evaluated at a different point in the pipeline.
    //
    // "reversal" used to live in this set. Split out below (Phase 1 of the reconciliation
    // roadmap, docs/proposals/reconciliation-evolution-roadmap-proposal.md) because a bank-side
    // reversal ("this payment bounced") and a merchant refund ("this order was returned") are
    // different real-world events that were producing an identical REFUND verdict -- the pass's
    // matching mechanism (same account, refund window, capacity tracking) is unchanged, only the
    // final classification now distinguishes them.
    private static final Set<String> REFUND_KEYWORDS = Set.of(
            "refund", "returned", "chargeback", "credit adjustment", "cancelled", "canceled");
    private static final Set<String> REVERSAL_KEYWORDS = Set.of("reversal", "payment reversed");

    // A run past this is worth a log line on its own. Chosen against the measurement in
    // scaling-triggers.md rather than picked as a round number: the windowed passes take ~105 ms
    // at 1k transactions and ~450 ms at 10k, so a second means either an unusually large history
    // or something that has regressed, and both are worth knowing about.
    private static final long SLOW_RUN_WARN_MS = 1_000;

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    // The roadmap doc's needs-review cutoff (Part 5): any match scoring below this becomes a
    // CANDIDATE edge instead of AUTO_CONFIRMED -- reusing TransactionRelationship.Status rather
    // than adding a separate needs_review column, since CANDIDATE already means exactly "written,
    // not yet trusted enough to stand on its own." This governs only the graph edge's status; the
    // Transaction row's own reconciliationStatus/legacy pointer is unaffected -- those stay the
    // simple, always-authoritative signal they have always been, and it is the more sophisticated
    // graph layer that carries the confidence-aware distinction for whatever review surface Phase
    // 2's Founder Operations Dashboard item eventually builds on top of it.
    private static final int NEEDS_REVIEW_THRESHOLD = 80;

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final RelationshipService relationshipService;
    private final AuditService auditService;
    private final TransactionGraphService transactionGraphService;
    private final com.finora.integrations.google.merchant.GmailReconciliationMatcher gmailReconciliationMatcher;
    private final com.finora.repository.StatementImportRepository statementImportRepository;

    public ReconciliationService(TransactionRepository transactionRepository, AccountRepository accountRepository,
                                  RelationshipService relationshipService,
                                  AuditService auditService, TransactionGraphService transactionGraphService,
                                  com.finora.integrations.google.merchant.GmailReconciliationMatcher gmailReconciliationMatcher,
                                  com.finora.repository.StatementImportRepository statementImportRepository) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.relationshipService = relationshipService;
        this.auditService = auditService;
        this.transactionGraphService = transactionGraphService;
        this.gmailReconciliationMatcher = gmailReconciliationMatcher;
        this.statementImportRepository = statementImportRepository;
    }

    /**
     * Runs after an import batch or manual edit. Same logic as the browser prototype, now with
     * a third pass added:
     * 1) Flag exact duplicates (same account+date+amount+description already present).
     * 2) Match internal transfers — an expense-side "payment" transaction on one account that
     *    lines up in amount and falls within 4 days of an income-side transaction on a
     *    different account is almost certainly money moving between the user's own accounts,
     *    not real income/spend, so both get tagged TRANSFER and excluded from totals.
     * 3) Match refunds — a same-account INCOME that reverses a specific prior EXPENSE (a
     *    return, a cancelled order, a billing dispute). Distinct from both of the above:
     *    same account (never different, unlike a transfer) and not an exact-field match
     *    (unlike a duplicate) -- the amount can be a partial refund, and the description
     *    routinely differs entirely from the original purchase's.
     */
    public void reconcileForUser(UUID userId) {
        // Deleted-account leak (see DashboardService.summarize for the original fix): a deleted
        // account's transactions deliberately keep deleted_at unset, so findByUserId alone would
        // keep re-matching them against a still-live account's rows forever, not just during
        // StatementImportService's 7-day grace window.
        List<UUID> liveAccountIds = accountRepository.findByUserId(userId).stream()
                .map(com.finora.entity.Account::getId).toList();
        List<Transaction> all = liveAccountIds.isEmpty() ? List.of()
                : transactionRepository.findByUserIdAndAccountIdIn(userId, liveAccountIds);
        // alwaysRecord=false: this is the per-edit path. See reconcile()'s emission block.
        reconcile(userId, all, Map.of("transactionsProcessed", all.size()), false);
    }

    /**
     * BH-041. The same passes, over the only transactions that can possibly match this import.
     *
     * <p><b>Why a second entry point rather than narrowing the first.</b>
     * {@link #reconcileForUser} has eight production callers — transaction create, update, delete,
     * bulk delete, statement delete and this pipeline — and at least one depends on the unbounded
     * re-scan: {@code TransactionService.clearReconciliationPointersTo} resets a surviving refund
     * row to OK precisely so "the next reconciliation pass" re-evaluates it, and that row can be
     * arbitrarily old. Narrowing the shared method would change all eight at once for the benefit
     * of one. This is the smaller blast radius.
     *
     * <p><b>Why the window is the right axis, and the account is not.</b> Transfers are
     * cross-account by definition (see the pass below: an expense on one account matched to an
     * income on a <i>different</i> one). Scoping to "the accounts in this import" would leave a
     * card payment unmatched whenever the savings leg arrived in an earlier import, and an
     * unmatched payment is counted as real spending — the double-count the transfer pass exists to
     * prevent. So every account stays in scope; only the date range narrows.
     *
     * <p>See {@link ReconciliationPolicy#CANDIDATE_WINDOW_DAYS} for what the window guarantees and
     * what it deliberately stops doing.
     *
     * @param earliestImported the earliest transaction date this import wrote, or null if it wrote
     *                         none — in which case this falls back to the unbounded pass rather
     *                         than inventing a window around nothing
     */
    public void reconcileForImport(UUID userId, LocalDate earliestImported, LocalDate latestImported) {
        if (earliestImported == null || latestImported == null) {
            reconcileForUser(userId);
            return;
        }
        LocalDate from = earliestImported.minusDays(ReconciliationPolicy.CANDIDATE_WINDOW_DAYS);
        LocalDate to = latestImported.plusDays(ReconciliationPolicy.CANDIDATE_WINDOW_DAYS);
        // Deleted-account leak (see reconcileForUser above for the original fix): a deleted
        // account's transactions deliberately keep deleted_at unset, so the unscoped finder would
        // keep re-matching them against a still-live account's rows forever.
        List<UUID> liveAccountIds = accountRepository.findByUserId(userId).stream()
                .map(com.finora.entity.Account::getId).toList();
        List<Transaction> candidates = liveAccountIds.isEmpty() ? List.of()
                : transactionRepository.findByUserIdAndTxnDateBetweenAndAccountIdIn(userId, from, to, liveAccountIds);
        // candidatesLoaded, NOT transactionsProcessed. The unbounded path's field means "how many
        // transactions this user has", and a scaling trend built on it would silently change
        // meaning the day an import started reporting a window instead. Two names, two meanings,
        // no discontinuity in either series.
        // alwaysRecord=true. An import is one event per uploaded statement, not one per keystroke
        // in the ledger, so its volume was never what BH-044 was about -- and this is the only
        // path that produces windowFrom/windowTo/candidatesLoaded, which is the sole record
        // anywhere of how much history a reconciliation pass actually read. Dropping it for a
        // quiet import would delete BH-041's evidence that the candidate window narrows anything.
        reconcile(userId, candidates, Map.of(
                "candidatesLoaded", candidates.size(),
                "windowFrom", from.toString(),
                "windowTo", to.toString()), true);
    }

    /**
     * The passes themselves, over whatever candidate set the caller established.
     *
     * @param scopeAudit how the caller describes its own scope, merged into the RECONCILIATION_RUN
     *                   audit row so a run says which shape it was
     * @param alwaysRecord whether this caller's runs are worth an audit row even when they
     *                     reclassify nothing. See the emission block below -- true for imports,
     *                     which are infrequent and carry scope telemetry nothing else records;
     *                     false for the per-edit path, which is where BH-044's volume came from
     */
    private void reconcile(UUID userId, List<Transaction> all, Map<String, Object> scopeAudit,
                           boolean alwaysRecord) {
        long startedAtNanos = System.nanoTime();

        // General retroactive edge cleanup (docs/proposals/reconciliation-evolution-roadmap-
        // proposal.md, Part 3's supersession gap). AccountService.delete() rejects graph edges for
        // every account deleted from now on, but an account deleted BEFORE that existed -- or
        // through any path that doesn't go through AccountService.delete -- can leave a live edge
        // of ANY relationship type (TRANSFER, REFUND, DUPLICATE, a Gmail cross-source match,
        // CC_PAYMENT) sitting in the graph, pointing at a transaction whose account the user can no
        // longer even see. Deliberately not scoped to one relationship type the way an earlier
        // version of this fix was: a stale edge from any pass has the identical failure mode
        // (excluding real, currently-visible money from cash flow to "net against" spend that no
        // longer exists anywhere the user can see it), so the fix belongs at this shared level, not
        // duplicated per pass.
        //
        // `all` is not a substitute for this lookup: reconcileForUser already excludes dead-account
        // rows from it, so there is nothing left in `all` to diff against; reconcileForImport
        // leaves every account in scope deliberately (see that method's own doc comment), so `all`
        // there can already contain dead-account rows without saying which ones. Both cases need
        // the same direct question answered the same way: an unscoped fetch of every transaction
        // this user has, checked against which accounts are still live.
        //
        // Real, not free: unlike the passes below, which all read the already-fetched `all`, this
        // is a genuinely new round trip on every single call -- this method runs synchronously
        // after every transaction create, update, delete, import confirm and statement delete, so
        // that cost lands on every one of them, for every user, indefinitely, not just for users
        // who have ever deleted an account. Accepted deliberately: the alternative (skip this and
        // leave the CC_PAYMENT-only version of the fix in place) traded correctness for that
        // saved query on every OTHER relationship type, and a wrong reconciliation number is worse
        // than one more indexed lookup. Revisit if SLOW_RUN_WARN_MS starts firing because of it --
        // narrowing this to run only when an account was actually just deleted (event-driven, the
        // way AccountService.delete's own forward-looking half already is) would remove the
        // per-call cost entirely, at the price of no longer self-healing data from before this fix.
        List<com.finora.entity.Account> liveAccounts = accountRepository.findByUserId(userId);
        Set<UUID> liveAccountIds = liveAccounts.stream()
                .map(com.finora.entity.Account::getId).collect(java.util.stream.Collectors.toSet());
        // Kept (not just the id set above) so the CC_PAYMENT pass below can read a card account's
        // own accountNumberMasked for last-4 matching without a second accountRepository round
        // trip -- see that pass's own comment.
        Map<UUID, com.finora.entity.Account> accountsById = liveAccounts.stream()
                .collect(java.util.stream.Collectors.toMap(com.finora.entity.Account::getId, a -> a));
        List<UUID> deadAccountTransactionIds = transactionRepository.findByUserId(userId).stream()
                .filter(t -> !liveAccountIds.contains(t.getAccountId()))
                .map(Transaction::getId)
                .toList();
        int staleEdgesRejected = deadAccountTransactionIds.isEmpty() ? 0
                : transactionGraphService.rejectEdgesTouchingTransactions(deadAccountTransactionIds);

        // 1) Duplicates -- grouped in-memory over the already-fetched `all` list rather than one
        // findPotentialDuplicates() query per transaction (the original shape of this pass, and
        // a real N+1: a user re-importing statements ends up re-running this after every import,
        // against their entire transaction history each time).
        //
        // stripTrailingZeros().toPlainString() on the amount, not amount.toString(), because the
        // original query used numeric SQL equality (100 = 100.00 is true) -- a naive string key
        // would treat differently-scaled-but-equal amounts as distinct groups, a real regression.
        // Null descriptions get a per-transaction-unique key component rather than colliding on
        // the literal string "null" -- NULL never equals NULL in the original query's SQL
        // semantics (`t.description = :description` with a null bind parameter is never true,
        // not even against another null), so two no-description transactions must never be
        // grouped as duplicates of each other either.
        // Counters for the Financial Intelligence Workspace's Reconciliation Monitor page --
        // "Matches created" per the kickoff memo's resolved scope (deterministic-only, no
        // confidence/near-miss logging) means new matches THIS run, not the lifetime total
        // already sitting on the transactions table (that lifetime total is what Workspace
        // Dashboard's duplicateMatches/transferMatches/refundMatches already show, computed
        // fresh on every read -- this is a different, complementary number: how much did the
        // most recent run actually do).
        int newDuplicates = 0, newTransfers = 0, newRefunds = 0, newReversals = 0, newGmailMatches = 0,
                newCcPaymentMatches = 0, newInvestmentTransfers = 0;

        // Every row the passes below touch, written once at the end instead of one save() per
        // match. A large first import can flag hundreds of duplicates, and each save() was its own
        // round trip -- the batch_size: 50 and order_updates: true already configured in
        // application.yml could not do anything for writes issued one statement at a time.
        //
        // A LinkedHashSet, not a list: the transfer pass touches BOTH sides of a pair and the same
        // transaction can be reached twice across passes, so this de-duplicates by identity while
        // keeping write order deterministic. Transaction has no equals()/hashCode() override, so
        // identity is exactly what this compares -- which is what is wanted here, since two
        // distinct rows are never interchangeable even when their fields match (that is the whole
        // subject of the duplicate pass).
        Set<Transaction> dirty = new LinkedHashSet<>();

        // Every graph edge the four passes below produce, written once via TransactionGraphService
        // .linkAll(...) at the end -- same reasoning as `dirty` above, and for the same run: an
        // import that flags hundreds of duplicates would otherwise mean hundreds of individual
        // exists-check-plus-save round trips against transaction_relationships.
        List<TransactionGraphService.PendingEdge> pendingEdges = new java.util.ArrayList<>();

        Map<String, List<Transaction>> byDuplicateKey = new HashMap<>();
        for (Transaction t : all) {
            if (t.getIsDuplicateOf() != null) continue; // already resolved by a prior run
            byDuplicateKey.computeIfAbsent(duplicateKey(t), k -> new java.util.ArrayList<>()).add(t);
        }
        for (List<Transaction> coarseGroup : byDuplicateKey.values()) {
            if (coarseGroup.size() < 2) continue;
            for (List<Transaction> group : splitByDiscriminator(coarseGroup)) {
                if (group.size() < 2) continue;
                // Canonical selection: higher SourceTrust wins outright (Phase 1 of the reconciliation
                // roadmap); creation order is only the tiebreak between two rows from the same source,
                // which is what this comparison degrades to when SourceTrust can't distinguish them --
                // exactly the behavior this pass had before source trust existed.
                Transaction canonical = group.stream()
                        .min(Comparator.<Transaction>comparingInt(t -> -SourceTrust.of(t.getSource()))
                                .thenComparing(Transaction::getCreatedAt))
                        .orElseThrow();
                for (Transaction t : group) {
                    if (t == canonical || t.getIsDuplicateOf() != null) continue;
                    // A human already ruled on this row and said it is a real, separate transaction.
                    // Nothing this pass can observe outranks that: it sees identical date, amount and
                    // description and cannot distinguish "the same statement uploaded twice" from "two
                    // metro fares on one day", which is why it needs to be told rather than left to
                    // infer. Marking it anyway is what made the ledger and the dashboard disagree by
                    // exactly the rows the user had asked for -- see V65.
                    //
                    // Checked here, inside the marking loop, rather than by excluding these rows from
                    // the grouping above: a confirmed row still belongs in its group so it can serve as
                    // `canonical`, and so a THIRD, genuinely accidental copy still gets flagged against
                    // it. Skipping the mark is the whole of the change; skipping the row is not.
                    if (t.getNotDuplicateConfirmedAt() != null) continue;
                    t.setIsDuplicateOf(canonical.getId());
                    t.setReconciliationStatus(Transaction.ReconciliationStatus.DUPLICATE);
                    // t and canonical are members of the same splitByDiscriminator sub-group, so
                    // when that group was split by balance/reference, EVERY member (both of these
                    // included) shares the identical value by construction -- a direct pairwise
                    // comparison here is equivalent to asking "was this group split by X" without
                    // splitByDiscriminator needing to report its own provenance back to callers.
                    boolean sameBalance = t.getBalanceAfter() != null && canonical.getBalanceAfter() != null
                            && t.getBalanceAfter().compareTo(canonical.getBalanceAfter()) == 0;
                    boolean sameReferenceNumber = t.getReferenceNumber() != null && canonical.getReferenceNumber() != null
                            && t.getReferenceNumber().equals(canonical.getReferenceNumber());
                    // Whether the two descriptions are identical verbatim or merely equal under the
                    // key's case/space folding (A2). The evidence below is read by a human
                    // comparing the two rows side by side, so it must not claim "same description"
                    // for two visibly different strings -- see ReconciliationExplanation.duplicate.
                    boolean descriptionsIdenticalVerbatim =
                            java.util.Objects.equals(t.getDescription(), canonical.getDescription());
                    Map<String, Object> explanation = ReconciliationExplanation.duplicate(
                            canonical.getId(), sameBalance, sameReferenceNumber, descriptionsIdenticalVerbatim);
                    t.setReconciliationExplanation(explanation);
                    dirty.add(t);
                    newDuplicates++;
                    // Exact composite-key match: no amount delta, no date window to decay across.
                    int duplicateConfidence = ConfidenceScorer.score(ConfidenceScorer.MatchType.EXACT,
                            t.getAmount(), BigDecimal.ZERO, 0, 0);
                    pendingEdges.add(new TransactionGraphService.PendingEdge(userId, t.getId(), canonical.getId(),
                            TransactionRelationship.RelationshipType.DUPLICATE, t.getAmount(), duplicateConfidence,
                            SourceTrust.of(t.getSource()), statusFor(duplicateConfidence),
                            TransactionRelationship.DetectionMethod.RULE_ENGINE, explanation));
                }
            }
        }

        // 2) Transfers
        //
        // Ordered by (txnDate, id), which does two things at once.
        //
        // Determinism first: findByUserId() carries no ORDER BY, so the order this pass used to
        // see was whatever Postgres happened to return -- unstable across plan changes and vacuum.
        // Both passes below stop at the first acceptable match, so that unspecified order was
        // silently deciding WHICH of several equally-valid pairs got matched. Sorting by date
        // makes that repeatable; the id tiebreak is what makes it repeatable on the same date too,
        // which is exactly when a same-day pair is most likely to have more than one candidate.
        //
        // And it is what lets the windowed lookups below work at all: both remaining passes only
        // ever match inside a bounded date range, so a sorted list turns "scan everything and
        // reject almost all of it" into a binary search plus a short contiguous slice. Measured:
        // see docs/engineering/scaling-triggers.md.
        List<Transaction> candidates = all.stream()
                .filter(t -> t.getIsDuplicateOf() == null && !t.isTransfer())
                .sorted(Comparator.comparing(Transaction::getTxnDate).thenComparing(Transaction::getId))
                .toList();

        // Fetched ONCE per reconcileForUser() call (not once per candidate, and not once per
        // pair inside the O(n^2) loop below) -- within a single run this is always the same
        // answer for the same user, so re-querying it per transaction would be a pure N+1 that
        // multiplies with every transaction on the books, on every import/create/edit/delete.
        List<String> ownAccountIdentifiers = relationshipService.ownAccountIdentifierValues(userId);
        Map<UUID, Boolean> ownAccountMatch = new HashMap<>();
        // Same reasoning as ownAccountMatch above -- CategoryRules.suggestCategory() walks every
        // compiled keyword pattern, so this is computed once per candidate rather than once per
        // pair-check inside the O(n^2) loop.
        Map<UUID, Boolean> looksLikeSalary = new HashMap<>();
        for (Transaction t : candidates) {
            String normalizedDescription = CategoryRules.normalize(t.getDescription());
            ownAccountMatch.put(t.getId(), ownAccountIdentifiers.stream().anyMatch(normalizedDescription::contains));
            looksLikeSalary.put(t.getId(), "Salary".equals(CategoryRules.suggestCategory(t.getDescription())));
        }

        for (Transaction a : candidates) {
            if (a.isTransfer()) continue;
            // Salary is external income, never money moving between the user's own accounts --
            // without this guard, a salary credit whose description happens to contain the word
            // "payment" (e.g. "NEFT SALARY PAYMENT XYZ CORP", a real-world pattern) could
            // false-positive-trigger this pass and, on an amount/date coincidence, get paired
            // against an unrelated expense as if it were a transfer.
            if (looksLikeSalary.getOrDefault(a.getId(), false)) continue;
            boolean aOwnAccountMatch = ownAccountMatch.getOrDefault(a.getId(), false);
            // A relationship identifier match is independent evidence of a known own-account
            // transfer -- it can trigger this candidate pair's evaluation on its own, without
            // also needing the "payment"-in-description heuristic.
            //
            // Reconciliation benchmark finding A (docs/proposals/reconciliation-benchmark/
            // benchmark-report.md, roadmap item #1): the plain "payment" substring alone missed
            // most real transfer-rail narrations against this project's own real bank-statement
            // corpus -- of every NEFT/IMPS/RTGS/UPI-shaped line across 29 real statements, only
            // ~1% also contained the word "payment". CategoryRules' own "Transfer" keyword list
            // ("neft to", "imps to", "autopay", "billdesk", "cc payment", "card bill payment") is
            // reused here rather than inventing new keywords -- it is already vetted,
            // word-boundary-matched vocabulary this project trusts for the identical
            // categorization decision, so widening this gate with it carries no new keyword-choice
            // risk. This does not close every gap the benchmark found (RTGS and a bare "self
            // transfer"/wallet narration carry none of these keywords either), by design -- see
            // the roadmap's own note that this is deliberately the smallest, lowest-risk slice,
            // re-measured against the benchmark before any further widening.
            boolean looksLikeTransfer = aOwnAccountMatch
                    || CategoryRules.normalize(a.getDescription()).contains("payment")
                    || "Transfer".equals(CategoryRules.suggestCategory(a.getDescription()));
            if (!looksLikeTransfer) continue;

            // Only the transactions that could possibly satisfy the daysApart check below, found
            // by binary search instead of by scanning and rejecting the rest. The slice uses the
            // WIDER of the two windows, because which one applies depends on b as well as a
            // (relationshipMatch reads ownAccountMatch for both sides) and is therefore not known
            // until the pair is in hand. The exact per-pair window check is unchanged and still
            // inside the loop -- this narrows what is considered, never what qualifies.
            //
            // Collects every qualifying candidate and scores it, rather than committing to the
            // first one found (reconciliation benchmark finding: docs/proposals/
            // reconciliation-benchmark/remaining-failures-classification.md, "first-match-wins"
            // misclassification). `candidates` is sorted by (date, id) for determinism (see its own
            // comment above), but sort order is not plausibility: a coincidental same-amount match
            // several days away previously won over the real transfer leg one day away, purely
            // because it happened to sort earlier -- transferCandidateScore below ranks every
            // qualifying candidate on the same evidence this pass already computes (own-account
            // relationship match, date proximity) instead of taking whichever is visited first.
            Transaction bestMatch = null;
            BigDecimal bestMatchAmountDelta = null;
            long bestMatchDaysApart = 0;
            long bestMatchDayWindow = 0;
            boolean bestMatchRelationshipMatch = false;
            int bestScore = Integer.MIN_VALUE;

            for (Transaction b : withinDays(candidates, a.getTxnDate(), OWN_ACCOUNT_MATCH_DAY_WINDOW)) {
                if (a.getId().equals(b.getId()) || b.isTransfer()) continue;
                if (looksLikeSalary.getOrDefault(b.getId(), false)) continue; // same guard, other side of the pair
                if (a.getAccountId().equals(b.getAccountId()) || a.getTxnType() == b.getTxnType()) continue;

                BigDecimal amountDelta = a.getAmount().subtract(b.getAmount()).abs();
                boolean sameAmount = amountDelta.compareTo(ReconciliationPolicy.TRANSFER_AMOUNT_TOLERANCE) < 0;
                if (!sameAmount) continue;
                long daysApart = Math.abs(ChronoUnit.DAYS.between(a.getTxnDate(), b.getTxnDate()));

                boolean relationshipMatch = aOwnAccountMatch || ownAccountMatch.getOrDefault(b.getId(), false);
                long dayWindow = relationshipMatch ? OWN_ACCOUNT_MATCH_DAY_WINDOW : DEFAULT_TRANSFER_DAY_WINDOW;
                if (daysApart > dayWindow) continue;

                int score = transferCandidateScore(relationshipMatch, daysApart, dayWindow);
                if (score > bestScore) {
                    bestScore = score;
                    bestMatch = b;
                    bestMatchAmountDelta = amountDelta;
                    bestMatchDaysApart = daysApart;
                    bestMatchDayWindow = dayWindow;
                    bestMatchRelationshipMatch = relationshipMatch;
                }
            }

            if (bestMatch != null) {
                Transaction b = bestMatch;
                BigDecimal amountDelta = bestMatchAmountDelta;
                long daysApart = bestMatchDaysApart;
                long dayWindow = bestMatchDayWindow;
                boolean relationshipMatch = bestMatchRelationshipMatch;

                a.setTransfer(true); b.setTransfer(true);
                a.setTransferPairId(b.getId()); b.setTransferPairId(a.getId());
                a.setReconciliationStatus(Transaction.ReconciliationStatus.TRANSFER);
                b.setReconciliationStatus(Transaction.ReconciliationStatus.TRANSFER);
                // Both sides get their own explanation, each naming the other. A transfer is
                // one decision but two rows, and someone looking at either row should not have
                // to fetch the partner to find out why this one was excluded from totals.
                Map<String, Object> explanationA = ReconciliationExplanation.transfer(a, b, dayWindow, relationshipMatch);
                Map<String, Object> explanationB = ReconciliationExplanation.transfer(b, a, dayWindow, relationshipMatch);
                a.setReconciliationExplanation(explanationA);
                b.setReconciliationExplanation(explanationB);
                dirty.add(a);
                dirty.add(b);
                newTransfers++; // one pair = one match, not two (see WorkspaceSummaryDto's own note on this asymmetry)
                // One edge per direction, matching transferPairId's own symmetric shape (see
                // V114's backfill comment) rather than picking an arbitrary canonical side.
                // MERCHANT_AND_AMOUNT tier: matched on amount + date window (+ an own-account
                // relationship identifier when relationshipMatch is true), not a free-text
                // merchant-name comparison -- there is no merchant on either side of a transfer
                // -- but it's the closest of the roadmap's three tiers to "two independent
                // structured signals agreeing," which is what this match actually is.
                int confidenceA = ConfidenceScorer.score(ConfidenceScorer.MatchType.MERCHANT_AND_AMOUNT,
                        a.getAmount(), amountDelta, daysApart, dayWindow);
                int confidenceB = ConfidenceScorer.score(ConfidenceScorer.MatchType.MERCHANT_AND_AMOUNT,
                        b.getAmount(), amountDelta, daysApart, dayWindow);
                pendingEdges.add(new TransactionGraphService.PendingEdge(userId, a.getId(), b.getId(),
                        TransactionRelationship.RelationshipType.TRANSFER, a.getAmount(), confidenceA,
                        SourceTrust.of(a.getSource()), statusFor(confidenceA),
                        TransactionRelationship.DetectionMethod.RULE_ENGINE, explanationA));
                pendingEdges.add(new TransactionGraphService.PendingEdge(userId, b.getId(), a.getId(),
                        TransactionRelationship.RelationshipType.TRANSFER, b.getAmount(), confidenceB,
                        SourceTrust.of(b.getSource()), statusFor(confidenceB),
                        TransactionRelationship.DetectionMethod.RULE_ENGINE, explanationB));
            }
        }

        // 2b) Investment transfers -- roadmap Phase 4's "investment... detection" item, scoped
        // down after checking this project's own real bank-statement corpus rather than building
        // all six enum-only relationship types speculatively (docs/proposals/
        // reconciliation-evolution-roadmap-proposal.md Part 10 explicitly defers all six until
        // "actual usage data ... shows which of the six users actually need first"). Every real
        // investment outflow in that corpus (UPI-GROWW INVEST TECH, UPI-ICCLGROWW-GROWW-BSE...)
        // was a single-entry savings-side debit with no matching credit-side Transaction anywhere
        // in Finora -- the user never imports the broker's own statement. A transaction_relationships
        // edge needs a real Transaction on both ends (see that entity's own doc comment), so this
        // deliberately does NOT write one; it reuses CategoryRules' existing "Investments" category
        // (already recognizes Groww/Zerodha/mutual fund/SIP/etc, no new keyword list duplicated
        // here) to exclude these rows from cash flow, the same way TRANSFER already is.
        //
        // Reads `candidates` (not `all`): those objects were already mutated in place by the
        // transfer pass above, so `t.isTransfer()` here reflects this run's own transfer matches,
        // not just ones from a prior run -- same reasoning the refund pass below applies to the
        // same list.
        for (Transaction t : candidates) {
            if (t.getTxnType() != Transaction.Type.EXPENSE) continue;
            if (t.isTransfer() || t.getReconciliationStatus() != Transaction.ReconciliationStatus.OK) continue;
            if (!"Investments".equals(CategoryRules.suggestCategory(t.getDescription()))) continue;
            t.setReconciliationStatus(Transaction.ReconciliationStatus.INVESTMENT_TRANSFER);
            t.setReconciliationExplanation(ReconciliationExplanation.investmentTransfer(t));
            dirty.add(t);
            newInvestmentTransfers++;
        }

        // 3) Refunds -- re-filters candidates fresh (not reusing the list above) because step 2
        // just mutated some of these same objects' isTransfer() in place; a transaction that
        // just became a transfer must not also be considered for a refund pairing.
        List<Transaction> refundCandidates = candidates.stream().filter(t -> !t.isTransfer()).toList();

        // BH-007. An expense's refund capacity is its own amount, shared across every income row
        // that might match it -- not a per-pair check. Without this, two ₹500 INCOME rows at the
        // same merchant within the window each independently satisfy "not more than the ₹500
        // EXPENSE", and both get marked REFUND: ₹1,000 of real income silently excluded from every
        // total on the strength of one ₹500 purchase.
        //
        // Seeded from already-resolved REFUND/REVERSAL rows already sitting in refundCandidates
        // (skipped by the `getReconciliationStatus() != OK` check below, same as a resolved
        // duplicate is skipped in pass 1), not just accumulated fresh in this loop -- a prior
        // run's match must count against this run's capacity too, not only matches made in the
        // same pass. Both statuses draw against the same expense's capacity: which of the two an
        // income row became doesn't change how much of the original purchase it accounts for.
        //
        // Correct across BOTH entry points without a new query: CANDIDATE_WINDOW_DAYS is
        // Math.max(REFUND_WINDOW_DAYS, ...), so it is never smaller than REFUND_WINDOW_DAYS by
        // construction. Any prior REFUND/REVERSAL row that could compete for the same expense as
        // a NEW income row in this run must itself sit within REFUND_WINDOW_DAYS of that expense
        // -- which means it is provably inside reconcileForImport's own fetch window too, so it
        // is always present in `all`/`refundCandidates` here, never silently missing.
        Map<UUID, BigDecimal> refundedSoFarByExpenseId = new HashMap<>();
        for (Transaction t : refundCandidates) {
            boolean isResolvedRefundLeg = t.getReconciliationStatus() == Transaction.ReconciliationStatus.REFUND
                    || t.getReconciliationStatus() == Transaction.ReconciliationStatus.REVERSAL;
            if (isResolvedRefundLeg && t.getRefundOfTransactionId() != null) {
                refundedSoFarByExpenseId.merge(t.getRefundOfTransactionId(), t.getAmount(), BigDecimal::add);
            }
        }

        for (Transaction income : refundCandidates) {
            if (income.getTxnType() != Transaction.Type.INCOME) continue;
            if (income.getReconciliationStatus() != Transaction.ReconciliationStatus.OK) continue;

            boolean refundKeyword = looksLikeRefund(income.getDescription());
            // Computed once per income row, same as refundKeyword above -- both are properties of
            // the income's own description, not of any particular candidate expense.
            boolean reversalKeyword = looksLikeReversal(income.getDescription());
            Transaction bestMatch = null;
            boolean bestMatchSameMerchant = false;
            long bestMatchDaysBetween = 0;
            // A refund lands at or after its purchase and within REFUND_WINDOW_DAYS of it, so the
            // only expenses worth looking at sit in [income.date - 180, income.date]. Both of
            // those bounds are still re-checked inside the loop; this only avoids walking the
            // years of history that provably cannot contain a match.
            for (Transaction expense : between(refundCandidates,
                    income.getTxnDate().minusDays(REFUND_WINDOW_DAYS), income.getTxnDate())) {
                if (expense.getTxnType() != Transaction.Type.EXPENSE) continue;
                // Same account only -- a refund lands back where the original purchase was made.
                // (This is exactly what distinguishes a refund from a transfer, which above
                // requires DIFFERENT accounts -- the two passes can never claim the same pair.)
                if (!expense.getAccountId().equals(income.getAccountId())) continue;
                if (income.getTxnDate().isBefore(expense.getTxnDate())) continue; // a refund can't predate its own purchase
                long daysBetween = ChronoUnit.DAYS.between(expense.getTxnDate(), income.getTxnDate());
                if (daysBetween > REFUND_WINDOW_DAYS) continue;

                // Need at least one real signal -- either the description says "refund"/
                // "reversal"/etc, or the (already-resolved, see Transaction.merchant) merchant
                // token matches the original purchase's. Neither alone is required everywhere;
                // all three being absent means there's no actual evidence this is a refund (or a
                // reversal) at all. The matching mechanism itself doesn't care which of the two
                // this turns out to be -- that's decided once, after a match is found, below.
                boolean sameMerchant = expense.getMerchant() != null && !expense.getMerchant().isBlank()
                        && expense.getMerchant().equalsIgnoreCase(income.getMerchant());
                if (!refundKeyword && !reversalKeyword && !sameMerchant) continue;

                // BH-007: capacity is what's LEFT of the expense, not its original amount -- an
                // expense already fully claimed by an earlier match (this pass or a prior one) has
                // nothing left to give, no matter how large it originally was.
                BigDecimal remaining = expense.getAmount()
                        .subtract(refundedSoFarByExpenseId.getOrDefault(expense.getId(), BigDecimal.ZERO));
                if (remaining.signum() <= 0 || income.getAmount().compareTo(remaining) > 0) continue;

                if (bestMatch == null || isCloserRefundMatch(expense, bestMatch, income)) {
                    bestMatch = expense;
                    // Carried out of the loop with the match it belongs to. sameMerchant is
                    // computed per candidate, so recomputing it after the loop would mean
                    // re-deriving a signal the pass had already established -- exactly the
                    // after-the-fact reconstruction this explanation exists to avoid.
                    bestMatchSameMerchant = sameMerchant;
                    bestMatchDaysBetween = daysBetween;
                }
            }

            if (bestMatch != null) {
                // Classification, not matching: the loop above found the same candidate expense
                // either way, using the same window/capacity/tiebreak rules. reversalKeyword takes
                // priority when present -- "reversal" is a specific bank-side signal, so a
                // description that happens to carry both a refund word and a reversal word (rare)
                // is read as the more specific claim. sameMerchant-only matches (no keyword at
                // all) stay REFUND, since a merchant match alone says nothing about *why* the
                // money came back.
                TransactionRelationship.RelationshipType edgeType;
                Map<String, Object> explanation;
                if (reversalKeyword) {
                    income.setReconciliationStatus(Transaction.ReconciliationStatus.REVERSAL);
                    explanation = ReconciliationExplanation.reversal(income, bestMatch, bestMatchSameMerchant);
                    income.setReconciliationExplanation(explanation);
                    newReversals++;
                    edgeType = TransactionRelationship.RelationshipType.REVERSAL;
                } else {
                    income.setReconciliationStatus(Transaction.ReconciliationStatus.REFUND);
                    explanation = ReconciliationExplanation.refund(income, bestMatch, refundKeyword, bestMatchSameMerchant);
                    income.setReconciliationExplanation(explanation);
                    newRefunds++;
                    edgeType = TransactionRelationship.RelationshipType.REFUND;
                }
                income.setRefundOfTransactionId(bestMatch.getId());
                dirty.add(income);
                // MERCHANT_AND_AMOUNT tier: matched on a refund/reversal keyword or merchant
                // identity, plus an amount that can't exceed the original purchase. matchedAmount
                // is the purchase's own amount (what there was to account for), not the income's --
                // a small partial refund against a large purchase should score lower than a full
                // one, which is exactly what amount_factor's delta-over-matchedAmount captures.
                BigDecimal refundAmountDelta = bestMatch.getAmount().subtract(income.getAmount()).abs();
                int refundConfidence = ConfidenceScorer.score(ConfidenceScorer.MatchType.MERCHANT_AND_AMOUNT,
                        bestMatch.getAmount(), refundAmountDelta, bestMatchDaysBetween, REFUND_WINDOW_DAYS);
                pendingEdges.add(new TransactionGraphService.PendingEdge(userId, income.getId(), bestMatch.getId(),
                        edgeType, income.getAmount(), refundConfidence, SourceTrust.of(income.getSource()),
                        statusFor(refundConfidence), TransactionRelationship.DetectionMethod.RULE_ENGINE, explanation));
                // Claims this income's amount against the expense's capacity immediately, so the
                // NEXT income row in this same pass sees the reduced remainder rather than the
                // original amount -- this is what makes two ₹500 incomes against one ₹500 expense
                // resolve to one REFUND/REVERSAL and one OK, not two.
                refundedSoFarByExpenseId.merge(bestMatch.getId(), income.getAmount(), BigDecimal::add);
            }
        }

        // 4) Gmail cross-source matches -- docs/proposals/reconciliation-evolution-roadmap-
        // proposal.md Part 5's confidence engine, extended to the one pass Phase 2's earlier PR
        // deliberately left out: GmailReconciliationMatcher already fuzzy-matches a Gmail receipt
        // against the bank ledger at STAGING time (amount exact, date window, Levenshtein merchant
        // similarity), but that match was never persisted -- confirming the receipt anyway created
        // a fully independent Transaction with no link back to the bank row it duplicates. This
        // makes that signal durable: a FUZZY-tier graph edge, the same infrastructure the other
        // three passes above already write to.
        //
        // Graph edge ONLY -- deliberately does NOT set isDuplicateOf/reconciliationStatus the way
        // the exact-match duplicate pass (1, above) does. That pass's legacy-column write is safe
        // unconditionally because its match is a deterministic composite-key equality; FUZZY is
        // this codebase's lowest confidence tier by design (Levenshtein threshold 0.6 admits real
        // ambiguity), and its score will almost always land below NEEDS_REVIEW_THRESHOLD -- auto-
        // excluding a legitimate expense from a user's spend totals off a fuzzy text match would be
        // a real correctness risk this first slice does not take. A CANDIDATE edge is visible in
        // the graph/explainability layer for review; today's dashboard totals are unchanged by this
        // pass. Whether a high-scoring Gmail match should ever auto-confirm is a follow-up decision,
        // not this one.
        List<Transaction> gmailExpenses = all.stream()
                .filter(t -> t.getSource() == Transaction.Source.GMAIL_IMPORT)
                .filter(t -> t.getTxnType() == Transaction.Type.EXPENSE)
                .toList();
        if (!gmailExpenses.isEmpty()) {
            Map<BigDecimal, List<Transaction>> bankExpensesByAmount = all.stream()
                    .filter(t -> t.getSource() != Transaction.Source.GMAIL_IMPORT)
                    .filter(t -> t.getTxnType() == Transaction.Type.EXPENSE)
                    .collect(java.util.stream.Collectors.groupingBy(Transaction::getAmount));
            int gmailWindowDays = com.finora.integrations.google.merchant.GmailReconciliationMatcher.DATE_WINDOW_DAYS;
            int[] gmailMatchesThisRun = {0};
            for (Transaction gmailTxn : gmailExpenses) {
                List<Transaction> gmailCandidates = bankExpensesByAmount
                        .getOrDefault(gmailTxn.getAmount(), List.of()).stream()
                        .filter(t -> Math.abs(ChronoUnit.DAYS.between(gmailTxn.getTxnDate(), t.getTxnDate())) <= gmailWindowDays)
                        .toList();
                if (gmailCandidates.isEmpty()) continue;

                gmailReconciliationMatcher.findMatchAmongTransactions(gmailTxn, gmailCandidates).ifPresent(matched -> {
                    long daysIntoWindow = Math.abs(ChronoUnit.DAYS.between(gmailTxn.getTxnDate(), matched.getTxnDate()));
                    // Exact-amount candidates only (see the groupingBy above), so amount_factor is
                    // always 1.0 here -- date_decay across the window is what actually varies.
                    int gmailConfidence = ConfidenceScorer.score(ConfidenceScorer.MatchType.FUZZY,
                            gmailTxn.getAmount(), BigDecimal.ZERO, daysIntoWindow, gmailWindowDays);
                    Map<String, Object> explanation = new java.util.LinkedHashMap<>();
                    explanation.put("type", "GMAIL_CROSS_SOURCE_MATCH");
                    explanation.put("matchedTransactionId", matched.getId().toString());
                    explanation.put("daysApart", daysIntoWindow);
                    pendingEdges.add(new TransactionGraphService.PendingEdge(userId, gmailTxn.getId(), matched.getId(),
                            TransactionRelationship.RelationshipType.DUPLICATE, gmailTxn.getAmount(), gmailConfidence,
                            SourceTrust.of(gmailTxn.getSource()), statusFor(gmailConfidence),
                            TransactionRelationship.DetectionMethod.RULE_ENGINE, explanation));
                    gmailMatchesThisRun[0]++;
                });
            }
            newGmailMatches = gmailMatchesThisRun[0];
        }

        // 5) Credit card payment matches -- roadmap Phase 3, "Credit card settlement" (docs/
        // proposals/reconciliation-evolution-roadmap-proposal.md Part 4). Phase 1 already extracts
        // a credit-card statement's totalAmountDue/paymentDueDate onto StatementImport at confirm
        // time; nothing has read them since. This pass links a savings-side payment to the specific
        // card transactions it settles -- CC_PAYMENT edges from the payment to every transaction
        // findByStatementImportId returns for that statement, since a CC_PAYMENT edge (like every
        // TransactionRelationship) needs a real Transaction on both ends and "the statement" itself
        // is scalar fields, not a row; the individual charges it billed are.
        //
        // CANDIDATE ONLY, unconditionally -- deliberately does NOT call statusFor(confidence) the
        // way every earlier pass does. Two reasons, not one: first, the same correctness argument
        // as the Gmail pass above (a wrong match here would silently exclude or double-count real
        // money -- and even with the last-4 disambiguation below, this pass still has no
        // destination-account-type check, and a last-4 match is real but bank-dependent evidence,
        // not a guarantee, per last4CandidatesIn's own doc comment); second, this pass runs AFTER
        // the TRANSFER pass in this same method specifically so it can read `!isTransfer()` and skip
        // anything TRANSFER already claimed -- a real credit-card bill payment is, today, already
        // caught by TRANSFER's generic "payment"-keyword/same-amount heuristic, so this pass only
        // ever sees the transactions TRANSFER left alone. AUTO_CONFIRMED status is not part of this
        // slice at all; see the roadmap doc and this pass's own PR description for why.
        // Unlike `all` above, deliberately NOT windowed by reconcileForImport's date range -- a
        // user's credit-card statement count is small (a handful per card per year), so scanning
        // every one of them on every run is cheap, and windowing it correctly would mean a second
        // date axis (statement period vs. payment date) this v1 slice doesn't need yet. Accepted
        // simplification, not an oversight; revisit if SLOW_RUN_WARN_MS ever fires because of it.
        List<StatementImport> ccStatements = statementImportRepository.findByUserIdAndTotalAmountDueIsNotNull(userId);
        // Deleted-account leak, same shape as reconcileForUser's own top-level fix (and the same
        // `liveAccountIds` computed there, above) -- a deleted account's transactions deliberately
        // keep deleted_at unset, so findByStatementImportId below would still return a dead card's
        // charges forever, not just during StatementImportService's 7-day grace window. Unlike
        // `all` (already scoped by reconcileForUser, but NOT by reconcileForImport -- see that
        // method's own doc comment on why it deliberately leaves every account in scope), this
        // statement lookup and the settledCharges lookup inside the loop both bypass account
        // scoping entirely. Without this filter, a dead card's statement stays processed, a live
        // savings-side payment gets claimed and excluded from cash flow to "settle" charges the
        // user can no longer even see -- real, currently-visible expense money silently vanishing
        // from reporting. The SAME liveness check is applied to paymentCandidates below, for a
        // mirror-image reason: reconcileForImport's `all` is deliberately account-unscoped, so a
        // stray payment sitting on a deleted SAVINGS account could otherwise win the closest-to-
        // due-date tiebreak over the real, live payment -- leaving the real payment un-excluded
        // from cash flow and the original double-count bug back, just reached through the import
        // path instead of the per-edit one.
        //
        // Any pre-existing CC_PAYMENT edge pointing at one of these dead statements' charges is
        // already handled -- the general cleanup above rejects it regardless of relationship type,
        // so this pass doesn't need its own copy of that retroactive step.
        if (!ccStatements.isEmpty()) {
            // Deterministic order for the claim-tracking below: findByUserIdAndTotalAmountDueIsNotNull
            // carries no ORDER BY, so without this, which statement wins a same-due-date/same-amount
            // coincidence (see claimedPaymentIds below) could vary run to run, writing a CC_PAYMENT
            // edge to a different charge set each time -- and since nothing supersedes the earlier
            // edge, contradictory live edges from one payment would accumulate across runs even
            // though any single run stays internally consistent. Earliest due date first (settle
            // the oldest bill first) is a reasonable tiebreak, not just an arbitrary stable one;
            // statement id breaks a further tie on the same due date.
            ccStatements = ccStatements.stream()
                    .filter(s -> liveAccountIds.contains(s.getAccountId()))
                    .sorted(Comparator.comparing(StatementImport::getPaymentDueDate,
                                    Comparator.nullsLast(Comparator.naturalOrder()))
                            .thenComparing(StatementImport::getId))
                    .toList();
        }
        if (!ccStatements.isEmpty()) {
            int[] ccMatchesThisRun = {0};
            Set<UUID> claimedPaymentIds = new HashSet<>();
            // Real card fragment matching (roadmap Part 4's "issuer-name + last-4-digit
            // matching"), confirmed feasible against this project's own real bank-statement
            // corpus rather than assumed -- see last4CandidatesIn's own doc comment for the ICICI
            // example that proved it and the HDFC one that didn't. Built once per run, not per
            // statement: every OTHER live card's last-4 needs to be known too, so a payment
            // description naming a DIFFERENT card can be excluded as a candidate, not just
            // deprioritized.
            Map<UUID, String> cardLast4ByAccountId = new java.util.HashMap<>();
            for (StatementImport s : ccStatements) {
                com.finora.entity.Account cardAccount = accountsById.get(s.getAccountId());
                String last4 = cardAccount == null ? null : last4Of(cardAccount.getAccountNumberMasked());
                if (last4 != null) cardLast4ByAccountId.put(s.getAccountId(), last4);
            }
            // signum() <= 0 excludes a statement with nothing owed (already paid off, or a $0
            // print) -- no payment to find in the first place, and would otherwise divide by
            // zero inside matchCcStatement's ratio check.
            List<StatementImport> validStatements = ccStatements.stream()
                    .filter(s -> s.getTotalAmountDue() != null && s.getTotalAmountDue().signum() > 0
                            && s.getPaymentDueDate() != null)
                    .toList();
            // Two phases, not one -- see matchCcStatement's own doc comment for why the widened
            // ratio window (partial payment/overpayment matching, below) makes this necessary:
            // an exact match anywhere must be claimed before any partial/overpayment search runs
            // at all, or an earlier-due-date statement's wide search can steal a payment that is
            // actually EXACT for a different, later-due-date statement.
            Set<UUID> settledStatementIds = new HashSet<>();
            for (StatementImport statement : validStatements) {
                if (matchCcStatement(userId, statement, all, liveAccountIds, claimedPaymentIds,
                        cardLast4ByAccountId, true, pendingEdges)) {
                    settledStatementIds.add(statement.getId());
                    ccMatchesThisRun[0]++;
                }
            }
            for (StatementImport statement : validStatements) {
                if (settledStatementIds.contains(statement.getId())) continue;
                if (matchCcStatement(userId, statement, all, liveAccountIds, claimedPaymentIds,
                        cardLast4ByAccountId, false, pendingEdges)) {
                    ccMatchesThisRun[0]++;
                }
            }
            newCcPaymentMatches = ccMatchesThisRun[0];
        }

        // One write for the whole run. Ordered and de-duplicated by the LinkedHashSet above, so
        // Hibernate's configured batch_size/order_updates can actually apply -- they could do
        // nothing when this was a save() per match.
        if (!dirty.isEmpty()) transactionRepository.saveAll(dirty);
        // Captured rather than discarded: linkAll returns only the edges it actually wrote, never
        // the ones its own idempotent dedup skipped (see that method's own doc comment) -- the
        // Gmail pass above re-evaluates every GMAIL_IMPORT transaction on every run with no
        // persisted "already matched" flag of its own, so pendingEdges is routinely non-empty on a
        // run that writes nothing new. writtenEdges is what changedSomething below actually needs.
        List<TransactionRelationship> writtenEdges = pendingEdges.isEmpty()
                ? List.of() : transactionGraphService.linkAll(pendingEdges);

        long elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000;

        // Financial Intelligence Workspace, Reconciliation Monitor -- one summary entry per run
        // THAT DID SOMETHING, not one per match (that would flood the activity feed with as many
        // rows as duplicates found in a single large import) and no longer one per run.
        //
        // BH-044. This used to record unconditionally, and said so: "Recorded even when every
        // counter is 0: 'ran and found nothing new' is itself the answer to 'when did this last
        // run'". That reasoning is being revisited deliberately rather than overlooked, so here is
        // why it does not hold.
        //
        // Reconciliation is synchronous and unconditional after every transaction create, update
        // and delete, every import confirm and every statement delete. So an all-zero run is
        // written at the same instant as the TRANSACTION_CREATED / TRANSACTION_UPDATED /
        // TRANSACTION_DELETED row that triggered it, carrying no fact that row does not already
        // carry, and "when did this last run" is answered by the trigger. What the zeros cost is
        // real: audit_logs has no retention, no partitioning and no archival, and this doubled its
        // growth rate against ordinary ledger editing.
        //
        // Three things keep their record, and they are what the original reasoning was actually
        // protecting:
        //
        //   dirty non-empty     -- the run CHANGED financial classification. That is the audit
        //                          trail's whole subject and is never dropped.
        //   elapsed >= slow     -- the run was expensive. durationMs exists to make a scaling
        //                          problem visible before a user reports one, and a slow no-op is
        //                          exactly the observation that matters; dropping it would remove
        //                          the evidence scaling-triggers.md asks for. The WARN below fires
        //                          on the same condition, so the log and the audit row agree.
        //   alwaysRecord        -- the caller says its runs are worth recording regardless. Only
        //                          reconcileForImport sets it, for two reasons: an import is one
        //                          event per uploaded statement rather than one per ledger edit, so
        //                          it was never the volume BH-044 named; and it is the ONLY source
        //                          of windowFrom/windowTo/candidatesLoaded, the sole record
        //                          anywhere of how much history a pass actually read.
        //
        // That third condition was added after the first version of this change broke
        // MultiSectionReconciliationCostIT.theCandidateSetExcludesHistoryOutsideTheWindow, which
        // reads candidatesLoaded off this row to prove BH-041's window narrows anything. Worth
        // recording rather than quietly patching: this audit row is not only a trail, it is the
        // only telemetry reconciliation scope has, and a test failing was the only thing that said
        // so. A cheaper emission fix would have deleted BH-041's evidence and left the suite green
        // except for one test that looked like it just needed its fixture adjusted.
        //
        // What is lost is the all-zero, fast, user-edit run -- a row saying nothing happened,
        // quickly, beside another row saying what did.
        //
        // durationMs and rowsWritten are new, and they are the two numbers that make a scaling
        // problem visible BEFORE a user reports one. This pass is synchronous on the request
        // thread and runs after every transaction create, update, delete, import confirm and
        // statement delete, so its cost is felt directly -- and until now the only way to know
        // what it cost was to reproduce it locally with a benchmark. A duration trend across real
        // accounts is the evidence scaling-triggers.md asks for, collected as a by-product of
        // ordinary use rather than as a special exercise.
        //
        // The scope fields come from the caller (see reconcileForImport on why the windowed path
        // reports candidatesLoaded rather than reusing transactionsProcessed).
        // !writtenEdges.isEmpty() joins this check because of the Gmail pass above: it is the
        // first pass that can write a real change (a graph edge) WITHOUT also touching `dirty` --
        // every earlier pass sets a legacy column alongside its edge, so dirty was always non-empty
        // whenever a NEW edge was written until now. writtenEdges (not pendingEdges) is what to
        // check: the Gmail pass has no persisted "already matched" flag of its own, so it
        // re-proposes the same edge on every run regardless of whether anything is actually new --
        // pendingEdges.isEmpty() would make nearly every future edit for a user with any Gmail
        // match audit-record, exactly the BH-044 noise this file spent real effort eliminating.
        // staleEdgesRejected joins this check for the same reason writtenEdges does: rejecting an
        // edge is a real graph mutation with no legacy-column counterpart, so a run that ONLY
        // rejected stale edges (no new dirty rows, no new written edges) would otherwise vanish
        // from the audit trail exactly the way BH-044 was originally worried about -- except here
        // the change is real, not noise.
        boolean changedSomething = !dirty.isEmpty() || !writtenEdges.isEmpty() || staleEdgesRejected > 0;
        boolean wasSlow = elapsedMs >= SLOW_RUN_WARN_MS;
        String recordedBecause = changedSomething ? "reclassified"
                : wasSlow ? "slow"
                : alwaysRecord ? "scope"
                : null;
        if (recordedBecause != null) {
            Map<String, Object> details = new java.util.LinkedHashMap<>(scopeAudit);
            details.put("duplicatesFound", newDuplicates);
            details.put("transfersMatched", newTransfers);
            details.put("refundsMatched", newRefunds);
            details.put("reversalsMatched", newReversals);
            details.put("investmentTransfersFound", newInvestmentTransfers);
            details.put("gmailMatchesFound", newGmailMatches);
            details.put("ccPaymentMatchesFound", newCcPaymentMatches);
            details.put("staleEdgesRejected", staleEdgesRejected);
            details.put("rowsWritten", dirty.size());
            details.put("durationMs", elapsedMs);
            // Says which condition put this row here, so a reader of the trail can tell "this run
            // reclassified something" from "this run was slow and found nothing" from "this run is
            // recorded because it is an import" without inferring it from the counters.
            details.put("recordedBecause", recordedBecause);
            auditService.record(userId, "RECONCILIATION_RUN", "Transaction", null, details);
        }

        // Logged as well as audited, at a level that costs nothing on a normal run. The audit
        // trail is per user and read through the admin UI; this is what makes a slow run visible
        // in ordinary log search when nobody yet knows which user to look at. WARN rather than
        // INFO past the threshold, because a reconciliation pass that takes over a second is the
        // condition scaling-triggers.md names, and it should not need someone to already be
        // looking for it.
        if (elapsedMs >= SLOW_RUN_WARN_MS) {
            log.warn("Reconciliation for user {} took {} ms over {} transactions ({} rows written). "
                            + "See docs/engineering/scaling-triggers.md -- this pass is synchronous on the request thread.",
                    userId, elapsedMs, all.size(), dirty.size());
        } else if (log.isDebugEnabled()) {
            log.debug("Reconciliation for user {}: {} transactions, {} ms, {} rows written.",
                    userId, all.size(), elapsedMs, dirty.size());
        }
    }

    private static TransactionRelationship.Status statusFor(int confidence) {
        return confidence >= NEEDS_REVIEW_THRESHOLD
                ? TransactionRelationship.Status.AUTO_CONFIRMED
                : TransactionRelationship.Status.CANDIDATE;
    }

    /**
     * Ranks one candidate the transfer pass is already considering -- called only after {@code b}
     * has already passed every structural check (different account, opposite type, not salary,
     * amount within tolerance, within the applicable day window). Deterministic, no stored history,
     * no learning: every input is something this pass already computes for the pair in hand.
     *
     * <p>Two signals, not the four-signal sketch this was scoped down from (own-account match, same
     * day, same description, known account pair) -- reconciliation benchmark's own before/after
     * measurement only needed these two to resolve both first-match-wins scenarios it found, and
     * this pass has no natural notion of "same description" for a transfer (the two legs of a real
     * transfer routinely read nothing alike -- "CREDIT CARD PAYMENT" vs. "PAYMENT RECEIVED THANK
     * YOU" -- unlike the duplicate pass, where identical description is the whole signal) or "known
     * account pair" (that would mean querying past confirmed edges between this specific pair of
     * accounts, real future work but a genuinely different, stateful mechanism from what a single
     * candidate's own fields can answer). Adding either needs its own evidence the way every other
     * change in this file has, not inclusion by default because a sketch mentioned it.
     *
     * @param relationshipMatch a user-configured OWN_ACCOUNT identifier matched on either side --
     *                          the strongest available evidence, worth more than any amount of date
     *                          proximity alone
     * @param daysApart         how far apart the two candidate dates are
     * @param dayWindow         the window this pair was matched under (4 or 10 days -- see
     *                          {@link ReconciliationPolicy}), so proximity is judged relative to
     *                          how wide a window was actually in play, not a fixed constant
     */
    private static int transferCandidateScore(boolean relationshipMatch, long daysApart, long dayWindow) {
        int score = relationshipMatch ? 40 : 0;
        if (dayWindow > 0) {
            // 20 at the anchor date, decaying linearly to 0 at the window's edge -- same shape as
            // ConfidenceScorer's own date_decay, deliberately: this project already treats "closer
            // to the anchor" as more plausible everywhere else a date window exists, and there is
            // no reason this pass's own candidate selection should reason about it differently.
            score += Math.round(20.0 * (dayWindow - daysApart) / dayWindow);
        }
        return score;
    }

    /**
     * Finds and links a single CC_PAYMENT match for one statement, returning whether it found
     * one. Extracted out of {@code reconcile()}'s CC_PAYMENT pass so that pass can run this
     * twice -- {@code requireExactAmount=true} for every statement first, then {@code false}
     * (partial payment/overpayment, roadmap Part 4) only for what's still unsettled.
     *
     * <p><b>Why two phases, not one.</b> The widened ratio window partial/overpayment matching
     * needs (roadmap Part 4) means a single payment amount can now be a PLAUSIBLE candidate for
     * statements with totally different printed totals -- not just ones it happens to share an
     * exact amount with. Searching every statement with the wide window in one pass, in due-date
     * order, let an earlier-due-date statement's own partial/overpayment search claim a payment
     * that was actually the EXACT match for a different, later-due-date statement, before that
     * statement ever got a chance to see it: e.g. a ₹2,500 payment sitting exactly at one card's
     * ₹1,000 statement's 2.5x overpayment ceiling, while also being precisely another card's
     * ₹2,500 statement -- the ₹1,000 statement, being due earlier and searched first, would
     * otherwise take a payment that unambiguously belongs to the other one. Resolving every
     * EXACT match globally first, across every statement, guarantees a partial/overpayment
     * search can only ever consume a payment no statement could exactly claim.
     */
    private boolean matchCcStatement(UUID userId, StatementImport statement, List<Transaction> all,
                                      Set<UUID> liveAccountIds, Set<UUID> claimedPaymentIds,
                                      Map<UUID, String> cardLast4ByAccountId, boolean requireExactAmount,
                                      List<TransactionGraphService.PendingEdge> pendingEdges) {
        String thisCardLast4 = cardLast4ByAccountId.get(statement.getAccountId());
        List<Transaction> paymentCandidates = all.stream()
                .filter(t -> t.getTxnType() == Transaction.Type.EXPENSE)
                .filter(t -> !t.isTransfer())
                .filter(t -> !claimedPaymentIds.contains(t.getId()))
                .filter(t -> liveAccountIds.contains(t.getAccountId()))
                .filter(t -> !t.getAccountId().equals(statement.getAccountId()))
                .filter(t -> requireExactAmount
                        ? t.getAmount().compareTo(statement.getTotalAmountDue()) == 0
                        : amountRatioInRange(t.getAmount(), statement.getTotalAmountDue()))
                .filter(t -> Math.abs(ChronoUnit.DAYS.between(t.getTxnDate(), statement.getPaymentDueDate()))
                        <= ReconciliationPolicy.CC_PAYMENT_DUE_DATE_WINDOW_DAYS)
                // A candidate whose OWN description names a DIFFERENT known card is excluded
                // outright -- real evidence it belongs elsewhere, not just a weaker candidate. A
                // candidate with no identifiable fragment at all, or one that matches THIS card,
                // is unaffected here; see the tiebreak below for how a match to THIS card is
                // preferred among what's left.
                .filter(t -> {
                    Set<String> descriptionLast4s = last4CandidatesIn(t.getDescription());
                    if (descriptionLast4s.isEmpty() || descriptionLast4s.contains(thisCardLast4)) return true;
                    return cardLast4ByAccountId.entrySet().stream()
                            .noneMatch(e -> !e.getKey().equals(statement.getAccountId())
                                    && descriptionLast4s.contains(e.getValue()));
                })
                .toList();
        if (paymentCandidates.isEmpty()) return false;

        // A candidate whose description names THIS card wins outright over one that doesn't,
        // even if the non-matching one is closer to the due date -- real evidence beats a
        // coincidence. Failing that, an EXACT-amount candidate beats a partial/overpaid one --
        // relevant only in the requireExactAmount=false phase, since the true-amount phase never
        // sees a partial/overpaid candidate at all. Among whatever's left tied on both, closest
        // to the printed due date wins, same tiebreak shape as the other passes above.
        Transaction payment = paymentCandidates.stream()
                .min(Comparator
                        .comparing((Transaction t) -> thisCardLast4 == null
                                || !last4CandidatesIn(t.getDescription()).contains(thisCardLast4))
                        .thenComparing((Transaction t) -> t.getAmount().compareTo(statement.getTotalAmountDue()) != 0)
                        .thenComparingLong(t -> Math.abs(ChronoUnit.DAYS.between(t.getTxnDate(), statement.getPaymentDueDate()))))
                .orElseThrow();
        boolean matchedByLast4 = thisCardLast4 != null
                && last4CandidatesIn(payment.getDescription()).contains(thisCardLast4);

        // EXPENSE only -- findByStatementImportId returns every transaction this statement's
        // confirm wrote, which can include an INCOME-type row for a credit/refund printed on the
        // same statement. A payment settles charges, not credits; an edge to a credit row would
        // be a nonsensical "this payment settles this refund" claim.
        List<Transaction> settledCharges = transactionRepository.findByStatementImportId(statement.getId())
                .stream().filter(t -> t.getTxnType() == Transaction.Type.EXPENSE).toList();
        if (settledCharges.isEmpty()) return false; // nothing on the card side to point the edge at -- payment stays unclaimed
        claimedPaymentIds.add(payment.getId());

        long daysFromDue = Math.abs(ChronoUnit.DAYS.between(payment.getTxnDate(), statement.getPaymentDueDate()));
        // EXACT, not MERCHANT_AND_AMOUNT, when the payment's own description names this specific
        // card -- real positive evidence, not just amount+date proximity (see matchedByLast4
        // above). MERCHANT_AND_AMOUNT otherwise, unchanged reasoning: the TRANSFER pass's own
        // precedent for reusing this tier for a non-merchant, structural signal. Status stays
        // CANDIDATE regardless either way -- see this pass's own comment above (in reconcile())
        // on why AUTO_CONFIRMED is out of scope for this slice; neither a last-4 match nor an
        // exact amount changes that verdict.
        ConfidenceScorer.MatchType matchType = matchedByLast4
                ? ConfidenceScorer.MatchType.EXACT : ConfidenceScorer.MatchType.MERCHANT_AND_AMOUNT;
        // The real gap between what was paid and what was owed, not the zero this pass hardcoded
        // before partial/overpayment matching existed -- ConfidenceScorer's own amountFactor
        // already scores a partial/overpaid match lower than an exact one (floored at 0.5, never
        // below); this just wires the real number in instead of pretending every match is exact.
        // matchedAmount is the STATEMENT's total, not the payment's own amount -- amountFactor's
        // own doc comment: it's "how much was there to account for" that anchors the ratio, the
        // same reasoning a partial refund's delta is measured against the original expense, not
        // the smaller amount refunded.
        BigDecimal amountDelta = payment.getAmount().subtract(statement.getTotalAmountDue()).abs();
        int ccConfidence = ConfidenceScorer.score(matchType,
                statement.getTotalAmountDue(), amountDelta, daysFromDue, ReconciliationPolicy.CC_PAYMENT_DUE_DATE_WINDOW_DAYS);
        // FULL / PARTIAL / OVERPAID for the explainability trail (roadmap Part 4) -- this pass
        // doesn't attempt per-charge pro-rated netting the roadmap's original design sketched
        // (the shipped v1 slice nets by excluding the PAYMENT transaction from cash flow
        // entirely, not by partially discounting each charge -- see
        // RefundNetting/ccPaymentFromTransactionIds), so a partial payment still links to every
        // settled charge below, same as a full one; this field is what tells a reader of the
        // graph that the link doesn't mean "the whole bill was paid."
        String paymentStatus = amountDelta.signum() == 0 ? "FULL"
                : payment.getAmount().compareTo(statement.getTotalAmountDue()) < 0 ? "PARTIAL" : "OVERPAID";
        Map<String, Object> explanation = new java.util.LinkedHashMap<>();
        explanation.put("type", "CC_PAYMENT_MATCH");
        explanation.put("statementImportId", statement.getId().toString());
        explanation.put("totalAmountDue", statement.getTotalAmountDue().toPlainString());
        explanation.put("paymentDueDate", statement.getPaymentDueDate().toString());
        explanation.put("daysFromDueDate", daysFromDue);
        explanation.put("matchedByLast4", matchedByLast4);
        explanation.put("paymentStatus", paymentStatus);
        for (Transaction charge : settledCharges) {
            if (charge.getId().equals(payment.getId())) continue; // a payment cannot settle itself
            pendingEdges.add(new TransactionGraphService.PendingEdge(userId, payment.getId(), charge.getId(),
                    TransactionRelationship.RelationshipType.CC_PAYMENT, payment.getAmount(), ccConfidence,
                    SourceTrust.of(payment.getSource()), TransactionRelationship.Status.CANDIDATE,
                    TransactionRelationship.DetectionMethod.RULE_ENGINE, explanation));
        }
        return true;
    }

    private static boolean amountRatioInRange(BigDecimal paymentAmount, BigDecimal totalAmountDue) {
        BigDecimal ratio = paymentAmount.divide(totalAmountDue, 4, java.math.RoundingMode.HALF_UP);
        return ratio.compareTo(ReconciliationPolicy.CC_PAYMENT_MIN_PARTIAL_RATIO) >= 0
                && ratio.compareTo(ReconciliationPolicy.CC_PAYMENT_MAX_OVERPAYMENT_RATIO) <= 0;
    }

    private static final java.util.regex.Pattern DIGIT_RUN = java.util.regex.Pattern.compile("\\d{4,}");

    /**
     * Every trailing-4-digit window from each run of 4+ consecutive digits in {@code text} -- the
     * shape a masked or reference-style card fragment takes in a real bank narration (roadmap
     * Part 4's "issuer-name + last-4-digit matching", verified against this project's own real
     * bank-statement corpus rather than assumed: an ICICI savings-side payment read
     * "BIL/INFT/.../CC BillPay-5001/Self" and the paid card's own printed number was
     * "5241XXXXXXXX5001" -- masking characters or a separator (hyphen, slash) already isolate the
     * real last-4 at the right boundary, so no bank-specific parsing is needed. Not every bank's
     * narration carries this (an HDFC sample's trailing digits did NOT match its own card's real
     * last-4, evidently a payment reference number instead) -- and a pure reference number
     * produces the identical shape with no way to tell it apart from a real card fragment by this
     * alone. See the caller: only a match against a transaction's OWN known card counts as
     * positive evidence; an unmatched candidate is treated as unknown, never as counter-evidence.
     */
    private static Set<String> last4CandidatesIn(String text) {
        if (text == null || text.isBlank()) return Set.of();
        Set<String> candidates = new HashSet<>();
        java.util.regex.Matcher m = DIGIT_RUN.matcher(text);
        while (m.find()) {
            String run = m.group();
            candidates.add(run.substring(run.length() - 4));
        }
        return candidates;
    }

    /**
     * The real last four digits of a masked account number, however the source bank formats the
     * mask -- "652925XXXXXX3123", "5241XXXXXXXX5001", "XXXX1234" all reduce correctly by simply
     * discarding every non-digit character and keeping the last four: a masking character is
     * never a digit, and every masked format this project has actually seen (PdfMetadataExtractor)
     * ends in the genuine trailing digits, never in mask characters. Null if there aren't at least
     * four digits to take -- an unmasked, malformed, or absent value.
     */
    private static String last4Of(String accountNumberMasked) {
        if (accountNumberMasked == null) return null;
        String digitsOnly = accountNumberMasked.replaceAll("[^0-9]", "");
        return digitsOnly.length() >= 4 ? digitsOnly.substring(digitsOnly.length() - 4) : null;
    }

    private String duplicateKey(Transaction t) {
        if (t.getDescription() == null) return "no-desc-" + t.getId();
        // A2 (two-pass mobile audit, 2026-09-01). Case- and space-folded, not the raw column
        // value: two extractions of the same underlying bank line (a CSV export vs. a re-scraped
        // PDF, or a bank that shifts capitalization between monthly exports) can differ by
        // nothing more than case or a stray leading/trailing space. Exact equality on the raw
        // string treats those as two different transactions -- a false negative that silently
        // double-counts a real one, which for a finance app is worse than the false positive
        // this key already accepts elsewhere (see the class comment on why over-matching here is
        // the safer failure mode: a wrongly-grouped pair costs the user one tap to un-duplicate,
        // a missed group costs them nothing they can see is wrong).
        //
        // Via the shared DuplicateMatching helper rather than an inlined trim().toLowerCase(),
        // because Java's trim() and SQL's TRIM() disagree about tabs and newlines and the three
        // duplicate paths have to answer identically -- see that class's own comment.
        return t.getAccountId() + "|" + t.getTxnDate() + "|"
                + t.getAmount().stripTrailingZeros().toPlainString() + "|"
                + com.finora.util.DuplicateMatching.normalizeDescription(t.getDescription());
    }

    /**
     * Bug fix. Same account+date+amount+description is not always the same transaction: a bank
     * can legitimately present several separate transactions on one day that share all four --
     * confirmed against two independent real statements (a PNB savings account with four ACH
     * mandate debits, and an HDFC savings account with four mutual-fund SIP installments), both
     * distinguished in the statement only by a declining running balance or a per-row reference
     * number. Grouping every one of them as a single duplicate cluster silently dropped the real
     * ones from every total.
     *
     * <p>Splits a {@code duplicateKey}-matched group by {@code balanceAfter} (the stronger
     * signal -- present whenever the statement prints a running balance, true for nearly every
     * savings-account import) or, failing that, {@code referenceNumber} (covers statements with a
     * per-row reference but no balance column, most commonly credit-card statements). Both are
     * best-effort, nullable staging-time extractions already carried on {@link Transaction} (see
     * its own doc comment) -- this does not change what is captured, only uses what was already
     * there.
     *
     * <p><b>Only splits when EVERY member of the group carries the discriminator.</b> Not "when
     * present" -- a group is split by a field only when every row in it has a value for that
     * field, never partially. A Gmail receipt import never captures a balance or reference
     * number; a later bank-statement import of that SAME real transaction does. Splitting
     * whenever either side merely lacks the value (rather than when the two sides actively
     * disagree) would leave that genuine duplicate unmatched -- silently double-counting it in
     * income/expense totals and the account balance, a worse failure than the false-positive
     * grouping this method exists to fix.
     *
     * <p><b>Reconciliation benchmark finding (docs/proposals/reconciliation-benchmark/
     * remaining-failures-classification.md): a group with NEITHER discriminator is not always
     * safe to leave as one cluster either.</b> Two genuinely separate recurring-mandate debits --
     * a SIP to one fund and a SIP to another, or two different loans' EMIs -- routinely share the
     * same round amount, the same day (both auto-debit on the 5th), and the same generic
     * bank-template narration, with no balance or reference column at all on a minimal-column
     * import. Merging them is silent DATA LOSS: the second row's amount vanishes from every total
     * with no signal anything happened, which this project treats as strictly worse than the
     * failure this whole method already accepts elsewhere (a genuine duplicate staying
     * unmatched -- see the paragraph above). {@link #looksLikeRecurringMandate} refuses to guess
     * for exactly this narrow, evidenced shape: when nothing else exists to tell two same-day,
     * same-amount rows apart AND the shared narration itself signals a recurring mandate, leave
     * every member of the group on its own rather than pick a canonical one and discard the rest.
     * Every other undiscriminated group (an ordinary same-day coincidence with no mandate marker)
     * is left exactly as it was before this guard existed: one cluster, matching everything else
     * this pass already did.
     */
    private static List<List<Transaction>> splitByDiscriminator(List<Transaction> group) {
        if (group.stream().allMatch(t -> t.getBalanceAfter() != null)) {
            return groupBy(group, t -> "bal:" + t.getBalanceAfter().stripTrailingZeros().toPlainString());
        }
        if (group.stream().allMatch(t -> t.getReferenceNumber() != null && !t.getReferenceNumber().isBlank())) {
            return groupBy(group, t -> "ref:" + t.getReferenceNumber());
        }
        if (looksLikeRecurringMandate(group.get(0).getDescription())) {
            return group.stream().map(List::of).toList();
        }
        return List.of(group);
    }

    // Deliberately a small, purpose-built set here rather than a reuse of CategoryRules.RULES --
    // that table is tuned for a different job (a user-facing category label, where "emi" alone is
    // excluded as a bare keyword specifically because it's a substring of "premium", see its own
    // comment) with a different cost of a false trigger. Here, a false trigger on an unrelated word
    // only means this pass declines to merge a group it otherwise would have -- the SAME accepted,
    // safer-direction failure (a genuine duplicate slips through) this method's own doc comment
    // already names for the balance/reference case above, not a new risk class. "ecs"/"nach" (the
    // clearing-system names Indian standing-instruction debits are routinely narrated with) and
    // "mandate"/"installment" are included alongside "sip"/"emi" for the same reason -- a real bank
    // template narration is as likely to say "ECS EMI AUTO DEBIT" as "EMI PAYMENT".
    private static final Set<String> RECURRING_MANDATE_MARKERS =
            Set.of("sip", "emi", "ecs", "nach", "mandate", "installment");

    private static boolean looksLikeRecurringMandate(String description) {
        if (description == null) return false;
        for (String token : CategoryRules.normalize(description).split(" ")) {
            if (RECURRING_MANDATE_MARKERS.contains(token)) return true;
        }
        return false;
    }

    private static List<List<Transaction>> groupBy(List<Transaction> group, java.util.function.Function<Transaction, String> keyFn) {
        Map<String, List<Transaction>> byKey = new HashMap<>();
        for (Transaction t : group) {
            byKey.computeIfAbsent(keyFn.apply(t), k -> new java.util.ArrayList<>()).add(t);
        }
        return new java.util.ArrayList<>(byKey.values());
    }

    // --- Date-windowed candidate lookup -------------------------------------------------------
    //
    // Both the transfer and refund passes are pair-matching loops whose match condition includes a
    // hard date bound -- 4 or 10 days for a transfer, 180 for a refund. A flat inner scan compares
    // every pair and then rejects almost all of them on that bound, which is work whose outcome
    // was knowable before it started. Measured at 50k transactions, that flat scan was 8.4 seconds
    // of synchronous work on a request-handling thread, run after every transaction create,
    // update, delete, import confirm and statement delete.
    //
    // These take a list already sorted by (txnDate, id) -- see where `candidates` is built -- and
    // return the contiguous slice that could possibly match. Every predicate the loops applied
    // before is still applied; this changes only how many candidates are offered to them, never
    // which ones qualify.

    /** The slice within {@code days} either side of {@code anchor}, inclusive. */
    private static List<Transaction> withinDays(List<Transaction> sortedByDate, LocalDate anchor, long days) {
        return between(sortedByDate, anchor.minusDays(days), anchor.plusDays(days));
    }

    /** The slice with {@code from <= txnDate <= to}. Empty when nothing falls in range. */
    private static List<Transaction> between(List<Transaction> sortedByDate, LocalDate from, LocalDate to) {
        int start = firstIndexOnOrAfter(sortedByDate, from);
        int end = firstIndexAfter(sortedByDate, to);
        // A view, not a copy -- the loops read it and mutate the Transaction objects themselves,
        // never the list's structure, so there is nothing to defend against by copying.
        return start >= end ? List.of() : sortedByDate.subList(start, end);
    }

    private static int firstIndexOnOrAfter(List<Transaction> sortedByDate, LocalDate date) {
        int low = 0, high = sortedByDate.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (sortedByDate.get(mid).getTxnDate().isBefore(date)) low = mid + 1;
            else high = mid;
        }
        return low;
    }

    private static int firstIndexAfter(List<Transaction> sortedByDate, LocalDate date) {
        int low = 0, high = sortedByDate.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (sortedByDate.get(mid).getTxnDate().isAfter(date)) high = mid;
            else low = mid + 1;
        }
        return low;
    }

    private boolean looksLikeRefund(String description) {
        String normalized = CategoryRules.normalize(description);
        return REFUND_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    private boolean looksLikeReversal(String description) {
        String normalized = CategoryRules.normalize(description);
        return REVERSAL_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    /** Exact-amount matches always outrank partial-amount matches; among equally-good matches,
     *  the temporally closer original purchase wins -- a simple, explainable tie-break rather
     *  than a scored heuristic, consistent with how this whole engine favors deterministic rules
     *  over ML-style scoring wherever a deterministic rule is adequate. */
    private boolean isCloserRefundMatch(Transaction candidate, Transaction currentBest, Transaction income) {
        boolean candidateExact = candidate.getAmount().compareTo(income.getAmount()) == 0;
        boolean currentExact = currentBest.getAmount().compareTo(income.getAmount()) == 0;
        if (candidateExact != currentExact) return candidateExact;

        long candidateDays = ChronoUnit.DAYS.between(candidate.getTxnDate(), income.getTxnDate());
        long currentDays = ChronoUnit.DAYS.between(currentBest.getTxnDate(), income.getTxnDate());
        return candidateDays < currentDays;
    }
}
