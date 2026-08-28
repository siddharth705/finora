package com.finora.service;

import com.finora.entity.Transaction;
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

    private final TransactionRepository transactionRepository;
    private final RelationshipService relationshipService;
    private final AuditService auditService;

    public ReconciliationService(TransactionRepository transactionRepository, RelationshipService relationshipService,
                                  AuditService auditService) {
        this.transactionRepository = transactionRepository;
        this.relationshipService = relationshipService;
        this.auditService = auditService;
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
        List<Transaction> all = transactionRepository.findByUserId(userId);
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
        List<Transaction> candidates = transactionRepository.findByUserIdAndTxnDateBetween(userId, from, to);
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
        int newDuplicates = 0, newTransfers = 0, newRefunds = 0, newReversals = 0;

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

        Map<String, List<Transaction>> byDuplicateKey = new HashMap<>();
        for (Transaction t : all) {
            if (t.getIsDuplicateOf() != null) continue; // already resolved by a prior run
            byDuplicateKey.computeIfAbsent(duplicateKey(t), k -> new java.util.ArrayList<>()).add(t);
        }
        for (List<Transaction> group : byDuplicateKey.values()) {
            if (group.size() < 2) continue;
            Transaction earliest = group.stream().min(Comparator.comparing(Transaction::getCreatedAt)).orElseThrow();
            for (Transaction t : group) {
                if (t == earliest || t.getIsDuplicateOf() != null) continue;
                // A human already ruled on this row and said it is a real, separate transaction.
                // Nothing this pass can observe outranks that: it sees identical date, amount and
                // description and cannot distinguish "the same statement uploaded twice" from "two
                // metro fares on one day", which is why it needs to be told rather than left to
                // infer. Marking it anyway is what made the ledger and the dashboard disagree by
                // exactly the rows the user had asked for -- see V65.
                //
                // Checked here, inside the marking loop, rather than by excluding these rows from
                // the grouping above: a confirmed row still belongs in its group so it can serve as
                // `earliest`, and so a THIRD, genuinely accidental copy still gets flagged against
                // it. Skipping the mark is the whole of the change; skipping the row is not.
                if (t.getNotDuplicateConfirmedAt() != null) continue;
                t.setIsDuplicateOf(earliest.getId());
                t.setReconciliationStatus(Transaction.ReconciliationStatus.DUPLICATE);
                t.setReconciliationExplanation(ReconciliationExplanation.duplicate(earliest.getId()));
                dirty.add(t);
                newDuplicates++;
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
            boolean looksLikeTransfer = aOwnAccountMatch || CategoryRules.normalize(a.getDescription()).contains("payment");
            if (!looksLikeTransfer) continue;

            // Only the transactions that could possibly satisfy the daysApart check below, found
            // by binary search instead of by scanning and rejecting the rest. The slice uses the
            // WIDER of the two windows, because which one applies depends on b as well as a
            // (relationshipMatch reads ownAccountMatch for both sides) and is therefore not known
            // until the pair is in hand. The exact per-pair window check is unchanged and still
            // inside the loop -- this narrows what is considered, never what qualifies.
            for (Transaction b : withinDays(candidates, a.getTxnDate(), OWN_ACCOUNT_MATCH_DAY_WINDOW)) {
                if (a.getId().equals(b.getId()) || b.isTransfer()) continue;
                if (looksLikeSalary.getOrDefault(b.getId(), false)) continue; // same guard, other side of the pair
                if (a.getAccountId().equals(b.getAccountId()) || a.getTxnType() == b.getTxnType()) continue;

                boolean sameAmount = a.getAmount().subtract(b.getAmount()).abs()
                        .compareTo(ReconciliationPolicy.TRANSFER_AMOUNT_TOLERANCE) < 0;
                long daysApart = Math.abs(ChronoUnit.DAYS.between(a.getTxnDate(), b.getTxnDate()));

                boolean relationshipMatch = aOwnAccountMatch || ownAccountMatch.getOrDefault(b.getId(), false);
                long dayWindow = relationshipMatch ? OWN_ACCOUNT_MATCH_DAY_WINDOW : DEFAULT_TRANSFER_DAY_WINDOW;

                if (sameAmount && daysApart <= dayWindow) {
                    a.setTransfer(true); b.setTransfer(true);
                    a.setTransferPairId(b.getId()); b.setTransferPairId(a.getId());
                    a.setReconciliationStatus(Transaction.ReconciliationStatus.TRANSFER);
                    b.setReconciliationStatus(Transaction.ReconciliationStatus.TRANSFER);
                    // Both sides get their own explanation, each naming the other. A transfer is
                    // one decision but two rows, and someone looking at either row should not have
                    // to fetch the partner to find out why this one was excluded from totals.
                    a.setReconciliationExplanation(
                            ReconciliationExplanation.transfer(a, b, dayWindow, relationshipMatch));
                    b.setReconciliationExplanation(
                            ReconciliationExplanation.transfer(b, a, dayWindow, relationshipMatch));
                    dirty.add(a);
                    dirty.add(b);
                    newTransfers++; // one pair = one match, not two (see WorkspaceSummaryDto's own note on this asymmetry)
                    break;
                }
            }
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
                if (reversalKeyword) {
                    income.setReconciliationStatus(Transaction.ReconciliationStatus.REVERSAL);
                    income.setReconciliationExplanation(
                            ReconciliationExplanation.reversal(income, bestMatch, bestMatchSameMerchant));
                    newReversals++;
                } else {
                    income.setReconciliationStatus(Transaction.ReconciliationStatus.REFUND);
                    income.setReconciliationExplanation(
                            ReconciliationExplanation.refund(income, bestMatch, refundKeyword, bestMatchSameMerchant));
                    newRefunds++;
                }
                income.setRefundOfTransactionId(bestMatch.getId());
                dirty.add(income);
                // Claims this income's amount against the expense's capacity immediately, so the
                // NEXT income row in this same pass sees the reduced remainder rather than the
                // original amount -- this is what makes two ₹500 incomes against one ₹500 expense
                // resolve to one REFUND/REVERSAL and one OK, not two.
                refundedSoFarByExpenseId.merge(bestMatch.getId(), income.getAmount(), BigDecimal::add);
            }
        }

        // One write for the whole run. Ordered and de-duplicated by the LinkedHashSet above, so
        // Hibernate's configured batch_size/order_updates can actually apply -- they could do
        // nothing when this was a save() per match.
        if (!dirty.isEmpty()) transactionRepository.saveAll(dirty);

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
        boolean changedSomething = !dirty.isEmpty();
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

    private String duplicateKey(Transaction t) {
        if (t.getDescription() == null) return "no-desc-" + t.getId();
        return t.getAccountId() + "|" + t.getTxnDate() + "|"
                + t.getAmount().stripTrailingZeros().toPlainString() + "|" + t.getDescription();
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
