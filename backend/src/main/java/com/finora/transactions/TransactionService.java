package com.finora.transactions;

import com.finora.dto.PagedResponse;
import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.security.OwnershipGuard;
import com.finora.service.AuditService;
import com.finora.service.BankManagementService;
import com.finora.service.CategorizationService;
import com.finora.service.ReconciliationService;
import com.finora.service.RecurringService;
import com.finora.service.SmsProvider;
import com.finora.util.CategoryRules;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TransactionService {

    private final TransactionRepository transactionRepository;
    private final CategoryRepository categoryRepository;
    private final AccountRepository accountRepository;
    private final CategorizationService categorizationService;
    private final ReconciliationService reconciliationService;
    private final RecurringService recurringService;
    private final AuditService auditService;
    private final BankManagementService bankManagementService;
    private final UserRepository userRepository;
    private final SmsProvider smsProvider;

    public TransactionService(TransactionRepository transactionRepository, CategoryRepository categoryRepository,
                               AccountRepository accountRepository,
                               CategorizationService categorizationService,
                               ReconciliationService reconciliationService,
                               RecurringService recurringService,
                               AuditService auditService,
                               BankManagementService bankManagementService,
                               UserRepository userRepository,
                               SmsProvider smsProvider) {
        this.transactionRepository = transactionRepository;
        this.categoryRepository = categoryRepository;
        this.accountRepository = accountRepository;
        this.categorizationService = categorizationService;
        this.reconciliationService = reconciliationService;
        this.recurringService = recurringService;
        this.auditService = auditService;
        this.bankManagementService = bankManagementService;
        this.userRepository = userRepository;
        this.smsProvider = smsProvider;
    }

    // Never a real bank id (BankRegistry ids are short uppercase codes like "PNB"/"OTHER") --
    // used as a stand-in for "no bank matched this search keyword" so the repository's
    // `a.bankId IN :bankIds` always binds a real, non-empty collection rather than an empty one,
    // sidestepping any JPA-provider-specific edge cases around empty IN(...) lists entirely.
    private static final String NO_BANK_MATCH_SENTINEL = "__NO_BANK_MATCH__";

    @Transactional(readOnly = true)
    // Bug fix: the repository call below already returns a Spring Data Page<Transaction>, which
    // computes totalElements/totalPages as part of the same query -- this used to throw that
    // metadata away and hand back a bare List, leaving the frontend with no way to know whether a
    // next page existed (see PagedResponse's own doc comment; the admin Users directory hit this
    // exact gap first and got a real fix, this endpoint didn't). Now returns the same envelope.
    public PagedResponse<TransactionDto> search(UUID userId, TransactionDto.FilterRequest f) {
        Sort sort = Sort.by(Sort.Direction.fromString(f.sortDir() == null ? "DESC" : f.sortDir()),
                f.sortField() == null ? "txnDate" : mapSortField(f.sortField()));
        // Bank-aware search (PRD's "Improve Search"): a keyword like "Punjab National" should
        // also match transactions on accounts held with that bank, not just description/merchant
        // text. bankManagementService.search() covers both the built-in registry and admin-added
        // custom banks (V26__custom_banks.sql) -- resolved here, one layer above the repository,
        // since neither is a database table the query could join against directly.
        List<String> matchingBankIds = f.keyword() != null && !f.keyword().isBlank()
                ? bankManagementService.search(f.keyword()).stream().map(com.finora.accounts.AccountDto.BankDto::id).toList()
                : List.of();
        List<String> bankIdsParam = matchingBankIds.isEmpty() ? List.of(NO_BANK_MATCH_SENTINEL) : matchingBankIds;
        // Bug fix: an unclamped negative page or oversized size reached PageRequest.of directly,
        // which throws IllegalArgumentException -- unhandled in GlobalExceptionHandler, so the
        // Ledger's own search endpoint 500'd on a malformed page param instead of just clamping it
        // the way every other paginated endpoint in this codebase does (see PageBounds).
        int safeSize = com.finora.util.PageBounds.safeSize(f.size() > 0 ? f.size() : 20);
        int safePage = com.finora.util.PageBounds.safePage(f.page());
        var page = transactionRepository.search(
                userId, f.accountId(), f.categoryId(),
                com.finora.util.EnumParsing.parseIfPresent(Transaction.Type.class, f.type(), "type"),
                f.dateFrom(), f.dateTo(), f.amountMin(), f.amountMax(), f.keyword(), bankIdsParam,
                PageRequest.of(safePage, safeSize, sort)
        );
        Map<UUID, String> namesById = categoryNamesById(userId);
        return PagedResponse.of(page.map(t -> TransactionDto.from(t, namesById.getOrDefault(t.getCategoryId(), "Uncategorized"))));
    }

    private Map<UUID, String> categoryNamesById(UUID userId) {
        return categoryRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(Category::getId, Category::getName));
    }

    private String mapSortField(String field) {
        return switch (field) {
            case "date" -> "txnDate";
            case "amount" -> "amount";
            default -> "txnDate";
        };
    }

    /**
     * How much a transaction of this type/amount moves its OWN account's running balance.
     * Savings/Wallet/Investment accounts follow the plain ledger convention (income adds,
     * expense subtracts). Credit cards are inverted: Account.balance represents money OWED, not
     * cash on hand — see DashboardService.computeHealthScore's debt-utilization math, which
     * divides balance by creditLimit and expects both to be positive magnitudes — so a purchase
     * (EXPENSE) increases what's owed and a payment/credit (INCOME) reduces it.
     */
    private BigDecimal balanceDelta(Account account, Transaction.Type type, BigDecimal amount) {
        boolean increases = account.getAccountType() == Account.Type.CREDIT_CARD
                ? type == Transaction.Type.EXPENSE
                : type == Transaction.Type.INCOME;
        return increases ? amount : amount.negate();
    }

    private void adjustAccountBalance(UUID accountId, BigDecimal delta) {
        if (delta.compareTo(BigDecimal.ZERO) == 0) return;
        accountRepository.findById(accountId).ifPresent(account -> {
            account.setBalance(account.getBalance().add(delta));
            accountRepository.save(account);
        });
        // If the account was itself deleted out from under this transaction, there's nothing to
        // adjust — findById is filtered by Account's own @SQLRestriction, same as everywhere
        // else in the app treats a since-deleted account as no longer live.
    }

    @Transactional
    public TransactionDto create(UUID userId, TransactionDto.CreateRequest req) {
        // Bug fix: req.accountId() used to go straight onto the transaction with no check that it
        // actually belongs to userId -- every other entry point into an Account (AccountService's
        // own getOwned, and this class's own getOwned for transactions) verifies ownership before
        // acting; this was the one place that didn't. Without it, any authenticated user could
        // POST here with another user's accountId and both plant a transaction pointed at it AND
        // silently move that victim's real account balance via adjustAccountBalance() below.
        Account account = getOwnedAccount(userId, req.accountId());

        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(account.getId());
        t.setTxnDate(req.date());
        t.setDescription(req.description());
        t.setMerchant(CategoryRules.extractMerchant(req.description()));
        requirePositiveAmount(req.amount());
        t.setAmount(req.amount());
        t.setTxnType(com.finora.util.EnumParsing.parse(Transaction.Type.class, req.type(), "type"));
        t.setTags(req.tags());
        t.setSource(Transaction.Source.MANUAL);

        String categoryName = req.categoryName();
        Category category;
        if (categoryName != null) {
            // An explicit category from the caller is a real decision — resolve it, learn from
            // it, and mark it manually set so the UI never shows it as an engine guess.
            t.setMerchantId(categorizationService.resolveMerchantId(userId, req.description()));
            category = categorizationService.resolveOrCreateCategory(userId, categoryName);
            categorizationService.learn(userId, req.description(), category.getId());
            t.setCategoryManuallySet(true);
            t.setDecisionSource(Transaction.DecisionSource.MANUAL);
        } else {
            // No explicit category given — ask the engine. A "default" (no rule/learned match)
            // suggestion isn't a real decision, so file it under Other but flag it for the
            // "Ask Once" review queue instead of silently learning a non-decision.
            var suggestion = categorizationService.suggest(userId, req.description(), req.amount(), null);
            t.setMerchantId(suggestion.merchantId()); // already resolved as part of suggest() — no need to resolve twice
            category = categorizationService.resolveOrCreateCategory(userId, suggestion.category());
            t.setNeedsCategoryReview(suggestion.source().equals("default"));
            t.setDecisionSource(suggestion.decisionSource());
            t.setDecisionRuleId(suggestion.ruleId());
            // create() is always a real write (unlike CsvImportService, there's no staging/
            // preview step in between) -- safe to record the match right here.
            categorizationService.recordRuleMatch(suggestion.ruleId());
        }
        t.setCategoryId(category.getId());
        // MARK_TRANSFER/MARK_INVESTMENT/ADD_TAG rules -- see CategorizationService.applySideEffectRules's
        // doc comment for why this runs after category/amount/merchant/txnType are all set, and
        // why it's safe (and intended) to override the category just assigned above. A
        // MARK_INVESTMENT match returns the new Category -- reassigning `category` here keeps
        // the response below in sync with what actually got persisted.
        Category sideEffectCategory = categorizationService.applySideEffectRules(userId, t);
        if (sideEffectCategory != null) {
            category = sideEffectCategory;
            // Bug fix: reassigning `category` alone didn't actually keep `t` in sync with it --
            // t.setCategoryId() above already ran against the PRE-override category, and nothing
            // called it again. The response (built from `category`) looked right; the persisted
            // transaction (built from `t`) silently kept the wrong one. Same bug, same fix, as
            // CsvImportService.confirm()'s equivalent side-effect-rule override -- see that
            // method's own comment on this exact pattern.
            t.setCategoryId(category.getId());
        }

        Transaction saved = transactionRepository.save(t);
        adjustAccountBalance(saved.getAccountId(), balanceOf(saved));
        // Both re-run synchronously, right after persistence, on every write path that can
        // change a user's transaction set -- same treatment for both detection engines now (see
        // docs/team-message-financial-intelligence-v1-closeout.md). Recurring detection can't
        // run before persistence the way an earlier draft pipeline diagram suggested: it works
        // by re-reading the user's transactions from the DB and grouping them, so it needs this
        // row (and its siblings) already saved, same precondition reconciliation already has.
        reconciliationService.reconcileForUser(userId);
        recurringService.detectForUser(userId);
        auditService.record(userId, "TRANSACTION_CREATED", "Transaction", saved.getId(),
                Map.of("amount", saved.getAmount(), "type", saved.getTxnType().name(), "source", saved.getSource().name()));
        sendTransactionAlert(userId, saved);
        return TransactionDto.from(saved, category.getName());
    }

    /** Real-time transaction alert SMS -- scoped deliberately to this one manual-entry path, not
     *  bulk statement import (CsvImportService/PdfImportService never call create(), so a 200-row
     *  statement import never fires 200 SMS). Requires a verified phone number, same trust bar as
     *  every other phone-number-dependent feature (see PhoneVerificationProvider) -- an
     *  unverified number is exactly the number a stranger could have mistyped at registration.
     *
     *  Bug fix: this used to run synchronously inside create()'s own @Transactional method, which
     *  meant a slow (or hanging) 2Factor API call held the DB connection for create()'s entire
     *  transaction -- and, worse, could send an alert for a transaction whose surrounding
     *  transaction later rolled back for an unrelated reason. Deferred to run only after the
     *  transaction actually commits, via TransactionSynchronizationManager -- falls back to
     *  sending immediately when no transaction synchronization is active (e.g. a unit test calling
     *  create() directly against a plain object, with no real Spring transaction in play). */
    private void sendTransactionAlert(UUID userId, Transaction t) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            doSendTransactionAlert(userId, t);
                        }
                    });
        } else {
            doSendTransactionAlert(userId, t);
        }
    }

    private void doSendTransactionAlert(UUID userId, Transaction t) {
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || !user.isPhoneVerified() || user.getPhoneNumber() == null) return;
        smsProvider.sendTransactionAlert(user.getPhoneNumber(), t.getDescription(), t.getAmount(), t.getTxnType().name());
    }

    private BigDecimal balanceOf(Transaction t) {
        Account account = accountRepository.findById(t.getAccountId()).orElse(null);
        return account == null ? BigDecimal.ZERO : balanceDelta(account, t.getTxnType(), t.getAmount());
    }

    /**
     * The whole balance-sign convention (see balanceDelta's doc comment) assumes amount is
     * always a non-negative magnitude, with direction encoded solely by the transaction's type.
     * A negative amount would silently double-invert that math -- e.g. an EXPENSE of -500 on a
     * savings account would ADD 500 to the balance instead of subtracting it. CSV imports are
     * already safe (CsvImportService.parseRow takes the absolute value), but nothing previously
     * stopped this from reaching the manual create/edit paths.
     */
    private void requirePositiveAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Amount must be greater than zero");
        }
    }

    /**
     * Full edit — the Ledger page's Edit action. Every editable field (date, description,
     * merchant, amount, type, category, notes, tags) can change in one call. If amount or type
     * changes, the account's running balance is corrected by reversing the transaction's old
     * contribution and applying its new one, so Dashboard/Accounts/Reports stay accurate without
     * needing a separate reconciliation pass. Category, when supplied, always counts as a manual
     * decision — there's no "engine suggestion" concept in an edit form the user is typing into.
     */
    @Transactional
    public TransactionDto update(UUID userId, UUID txnId, TransactionDto.UpdateRequest req) {
        Transaction t = getOwned(userId, txnId);

        BigDecimal oldDelta = balanceOf(t);

        if (req.date() != null) t.setTxnDate(req.date());
        if (req.description() != null) t.setDescription(req.description());
        if (req.merchant() != null) t.setMerchant(req.merchant());
        if (req.amount() != null) {
            requirePositiveAmount(req.amount());
            t.setAmount(req.amount());
        }
        if (req.type() != null) t.setTxnType(com.finora.util.EnumParsing.parse(Transaction.Type.class, req.type(), "type"));
        if (req.notes() != null) t.setNotes(req.notes());
        if (req.tags() != null) t.setTags(req.tags());

        Category category = null;
        if (req.categoryName() != null) {
            category = categorizationService.resolveOrCreateCategory(userId, req.categoryName());
            t.setCategoryId(category.getId());
            t.setCategoryManuallySet(true);
            t.setNeedsCategoryReview(false); // an explicit edit always resolves the review flag, even choosing "Other" on purpose
            t.setDecisionSource(Transaction.DecisionSource.MANUAL);
            t.setDecisionRuleId(null);
            categorizationService.learn(userId, t.getDescription(), category.getId());
        }

        Transaction saved = transactionRepository.save(t);

        BigDecimal newDelta = balanceOf(saved);
        adjustAccountBalance(saved.getAccountId(), newDelta.subtract(oldDelta));

        // Amount/date/type edits can change which surviving transactions look like duplicates or
        // transfer partners of this one, so re-run reconciliation rather than leaving stale flags.
        // A merchant/amount edit can equally change whether this transaction still fits (or now
        // fits) a recurring group, so recurring detection re-runs here too.
        reconciliationService.reconcileForUser(userId);
        recurringService.detectForUser(userId);

        String resolvedCategoryName = category != null
                ? category.getName()
                : categoryNamesById(userId).getOrDefault(saved.getCategoryId(), "Uncategorized");
        auditService.record(userId, "TRANSACTION_UPDATED", "Transaction", txnId, Map.of("amount", saved.getAmount()));
        return TransactionDto.from(saved, resolvedCategoryName);
    }

    @Transactional
    public TransactionDto updateCategory(UUID userId, UUID txnId, String categoryName) {
        Transaction t = getOwned(userId, txnId);
        String previousCategoryId = String.valueOf(t.getCategoryId());
        Category category = categorizationService.resolveOrCreateCategory(userId, categoryName);
        t.setCategoryId(category.getId());
        t.setNeedsCategoryReview(false); // an explicit choice always resolves the review flag, even if they pick "Other" on purpose
        t.setCategoryManuallySet(true);
        t.setDecisionSource(Transaction.DecisionSource.MANUAL);
        t.setDecisionRuleId(null);
        categorizationService.learn(userId, t.getDescription(), category.getId());
        Transaction saved = transactionRepository.save(t);
        auditService.record(userId, "TRANSACTION_CATEGORY_UPDATED", "Transaction", txnId,
                Map.of("previousCategoryId", previousCategoryId, "newCategory", categoryName));
        return TransactionDto.from(saved, category.getName());
    }

    /**
     * Backs POST /api/v1/merchants/{merchantId}/confirm-category (spec §5.5) -- the
     * merchant-centric counterpart to updateCategory() above. Functionally the same three
     * things (set the transaction's category, mark it manually-set/reviewed, record the
     * confirmation against the merchant's learned distribution via
     * categorizationService.learn()) -- categorizationService.learn() already resolves the
     * transaction's merchant and calls MerchantLearningService.confirm() internally, so this
     * was never missing that wiring, only the merchant-centric endpoint shape spec'd by §5.5
     * (categoryId + a specific transaction to apply it to, rather than updateCategory()'s
     * transaction-centric categoryName).
     *
     * The merchantId check below is what the spec means by "replaces...for merchant-resolved
     * transactions" -- confirming against the wrong merchant (or a transaction with no resolved
     * merchant at all) is rejected rather than silently confirming against mismatched learning
     * data; the spec's own text says unresolved transactions "fall back to the existing simpler
     * endpoint" (updateCategory()), not this one.
     */
    @Transactional
    public TransactionDto confirmMerchantCategory(UUID userId, UUID merchantId, UUID txnId, UUID categoryId) {
        Transaction t = getOwned(userId, txnId);
        if (t.getMerchantId() == null || !t.getMerchantId().equals(merchantId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "This transaction isn't resolved to the given merchant -- use PATCH /transactions/{id}/category instead.");
        }
        Category category = categoryRepository.findById(categoryId)
                .filter(c -> c.getUserId().equals(userId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Category not found."));

        String previousCategoryId = String.valueOf(t.getCategoryId());
        t.setCategoryId(category.getId());
        t.setNeedsCategoryReview(false);
        t.setCategoryManuallySet(true);
        t.setDecisionSource(Transaction.DecisionSource.MANUAL);
        t.setDecisionRuleId(null);
        categorizationService.learn(userId, t.getDescription(), category.getId());
        Transaction saved = transactionRepository.save(t);
        auditService.record(userId, "TRANSACTION_CATEGORY_UPDATED", "Transaction", txnId,
                Map.of("previousCategoryId", previousCategoryId, "newCategory", category.getName()));
        return TransactionDto.from(saved, category.getName());
    }

    /** Backs the "Ask Once, Learn Forever" review queue — every transaction the engine wasn't
     *  confident about, waiting on exactly one user decision each. */
    @Transactional(readOnly = true)
    public List<TransactionDto> needsReview(UUID userId) {
        Map<UUID, String> namesById = categoryNamesById(userId);
        return transactionRepository.findByUserIdAndNeedsCategoryReviewTrueOrderByTxnDateDesc(userId).stream()
                .map(t -> TransactionDto.from(t, namesById.getOrDefault(t.getCategoryId(), "Uncategorized")))
                .toList();
    }

    @Transactional
    public void delete(UUID userId, UUID txnId) {
        Transaction t = getOwned(userId, txnId);
        clearReconciliationPointersTo(List.of(t.getId()));
        adjustAccountBalance(t.getAccountId(), balanceOf(t).negate());
        transactionRepository.delete(t); // soft delete via @SQLDelete on the entity
        // Removing a transaction can break a recurring group's pattern (e.g. deleting one of
        // three regularly-spaced charges), same reasoning as the reconciliation re-run below.
        reconciliationService.reconcileForUser(userId);
        recurringService.detectForUser(userId);
        auditService.record(userId, "TRANSACTION_DELETED", "Transaction", txnId,
                Map.of("amount", t.getAmount(), "description", String.valueOf(t.getDescription())));
    }

    @Transactional
    public void bulkDelete(UUID userId, List<UUID> ids) {
        List<Transaction> owned = ids.stream().map(id -> getOwned(userId, id)).toList();
        clearReconciliationPointersTo(owned.stream().map(Transaction::getId).toList());
        for (Transaction t : owned) {
            adjustAccountBalance(t.getAccountId(), balanceOf(t).negate());
            transactionRepository.delete(t);
        }
        reconciliationService.reconcileForUser(userId);
        recurringService.detectForUser(userId);
        auditService.record(userId, "TRANSACTION_BULK_DELETED", "Transaction", null,
                Map.of("count", ids.size(), "ids", ids));
    }

    /**
     * Any surviving transaction that had been paired with one of the removed ones (as a
     * duplicate, transfer partner, or refund target) gets its reconciliation flags reset first,
     * rather than being left pointing at a row that no longer visibly exists — same cleanup
     * StatementImportService.delete() already does for whole-statement deletes, now shared by
     * single/bulk transaction delete too, which never did this before.
     *
     * Bug fix: the refund case was missing entirely -- deleting the EXPENSE side of a matched
     * refund pair left the INCOME row's refundOfTransactionId dangling (pointing at a row that no
     * longer exists) AND stuck at ReconciliationStatus.REFUND forever, since reconcileForUser()
     * only ever matches a fresh REFUND, it never re-validates or clears an existing one. That
     * silently kept excluding real income from DashboardService's totals (REFUND rows are
     * excluded there the same way DUPLICATE/TRANSFER are) with no way to self-correct. Resetting
     * to OK here lets the next reconciliation pass re-evaluate it like any other income row.
     */
    private void clearReconciliationPointersTo(List<UUID> removedIds) {
        if (removedIds.isEmpty()) return;
        for (Transaction t : transactionRepository.findByIsDuplicateOfIn(removedIds)) {
            if (removedIds.contains(t.getId())) continue;
            t.setIsDuplicateOf(null);
            t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
            transactionRepository.save(t);
        }
        for (Transaction t : transactionRepository.findByTransferPairIdIn(removedIds)) {
            if (removedIds.contains(t.getId())) continue;
            t.setTransfer(false);
            t.setTransferPairId(null);
            t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
            transactionRepository.save(t);
        }
        for (Transaction t : transactionRepository.findByRefundOfTransactionIdIn(removedIds)) {
            if (removedIds.contains(t.getId())) continue;
            t.setRefundOfTransactionId(null);
            t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
            transactionRepository.save(t);
        }
    }

    @Transactional
    public void bulkRecategorize(UUID userId, List<UUID> ids, String categoryName) {
        Category category = categorizationService.resolveOrCreateCategory(userId, categoryName);
        for (UUID id : ids) {
            Transaction t = getOwned(userId, id);
            t.setCategoryId(category.getId());
            t.setNeedsCategoryReview(false); // an explicit bulk choice resolves the review flag too — see updateCategory()
            t.setCategoryManuallySet(true);
            t.setDecisionSource(Transaction.DecisionSource.MANUAL);
            t.setDecisionRuleId(null);
            categorizationService.learn(userId, t.getDescription(), category.getId());
            transactionRepository.save(t);
        }
        auditService.record(userId, "TRANSACTION_BULK_RECATEGORIZED", "Transaction", null,
                Map.of("count", ids.size(), "newCategory", categoryName));
    }

    private Transaction getOwned(UUID userId, UUID txnId) {
        return OwnershipGuard.requireOwned(
                transactionRepository.findById(txnId), Transaction::getUserId, userId, "Transaction");
    }

    /** The same check AccountService applies -- both now route through {@link OwnershipGuard}
     *  rather than each keeping its own copy. This method survives only as a named shorthand for
     *  the label/getter pair; the security logic itself lives in exactly one place. */
    private Account getOwnedAccount(UUID userId, UUID accountId) {
        return OwnershipGuard.requireOwned(
                accountRepository.findById(accountId), Account::getUserId, userId, "Account");
    }
}
