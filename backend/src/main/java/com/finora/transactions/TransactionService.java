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
import com.finora.service.SmsResult;
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
        // BH-009: sortDir went into Sort.Direction.fromString unvalidated, so ?sortDir=bogus threw
        // IllegalArgumentException and 500'd -- in the same method whose own comment two blocks
        // down explains that page and size are clamped precisely so a malformed param stops doing
        // that. Two of the three inputs were fixed and the third was missed.
        //
        // fromOptionalString, so an unrecognised value falls back to the default rather than
        // failing the search. That matches how sortField already behaves (mapSortField's `default`
        // arm quietly yields txnDate) -- a sort direction is a presentation preference, and
        // refusing to return a user's transactions over one would be a worse answer than sorting
        // them the usual way.
        Sort sort = Sort.by(
                Sort.Direction.fromOptionalString(f.sortDir() == null ? "" : f.sortDir())
                        .orElse(Sort.Direction.DESC),
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
                f.dateFrom(), f.dateTo(), f.amountMin(), f.amountMax(),
                // Escaped for LIKE (see LikePatterns) -- transaction descriptions are full of
                // literal percent signs ("2.5% CASHBACK"), and an unescaped one turned an exact
                // search into a prefix search silently. Only the repository term is escaped:
                // bankManagementService.search() above matches in memory with contains().
                com.finora.util.LikePatterns.escape(f.keyword()), bankIdsParam,
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
        // Delegates to AccountBalanceConvention, which now owns this rule. It used to live here as
        // a private method, which is precisely why ImportService could not reuse it and shipped
        // Bug 17 -- a confirmed statement inserted its rows and never moved the balance. Same
        // arithmetic, one owner.
        return com.finora.accounts.AccountBalanceConvention.balanceDelta(
                account.getAccountType(), type, amount);
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

    /**
     * SEC-06 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). Checked first, and
     * deliberately a plain findByUserIdAndIdempotencyKey rather than an insert-and-catch: the
     * common case (no key, or a genuinely first attempt) must not pay for an exception path, and
     * this mirrors the "check first, let the database catch the race" shape ImportJobService.accept
     * and ImportSessionService.parseAndStageWithSession already use for the equivalent import-side
     * check (see V74/V97's own migration comments). A concurrent duplicate that slips past this
     * read loses the race at the unique index (V97) instead, and gets GlobalExceptionHandler's
     * existing 409 for DataIntegrityViolationException -- the same outcome the import-side callers
     * already accept, and a retry of that same request lands on this early-return branch instead.
     */
    @Transactional
    public TransactionDto create(UUID userId, TransactionDto.CreateRequest req) {
        // Blank normalized to null up front, and reused below for both the check and the persisted
        // value -- an empty string is not a real key (V97's index would otherwise start treating
        // "every caller that sends an empty string" as one colliding identity), and a caller that
        // omits the field entirely already sends null, so both spellings of "no key" must behave
        // identically rather than one of them silently opting into idempotency by accident.
        String idempotencyKey = (req.idempotencyKey() == null || req.idempotencyKey().isBlank())
                ? null : req.idempotencyKey();
        if (idempotencyKey != null) {
            Transaction existing = transactionRepository
                    .findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                    .orElse(null);
            if (existing != null) {
                String categoryName = categoryRepository.findById(existing.getCategoryId())
                        .map(Category::getName).orElse("Uncategorized");
                return TransactionDto.from(existing, categoryName);
            }
        }

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
        requireAmountWithinBounds(req.amount());
        t.setAmount(req.amount());
        t.setTxnType(com.finora.util.EnumParsing.parse(Transaction.Type.class, req.type(), "type"));
        t.setTags(req.tags());
        t.setSource(Transaction.Source.MANUAL);
        t.setIdempotencyKey(idempotencyKey);

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
        SmsResult result = smsProvider.sendTransactionAlert(
                user.getPhoneNumber(), t.getDescription(), t.getAmount(), t.getTxnType().name());
        auditService.record(userId, "SMS_SENT", "User", userId, Map.of(
                "type", "transaction_alert", "provider", result.provider().name(), "success", result.success()));
    }

    private BigDecimal balanceOf(Transaction t) {
        Account account = accountRepository.findById(t.getAccountId()).orElse(null);
        return account == null ? BigDecimal.ZERO : balanceDelta(account, t.getTxnType(), t.getAmount());
    }

    /**
     * SEC-13 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). The DB column
     * (NUMERIC(14,2)) already stops anything past 12 integer digits, but as a raw
     * DataIntegrityViolationException rather than a validation error naming the field -- and 12
     * digits (nearly a trillion) is not "a bound," it is the column simply running out of room.
     * This is the actual sanity ceiling: a manually entered amount above what any real bank
     * statement would plausibly contain, rejected with a message the user can act on instead of a
     * generic 409.
     */
    private static final BigDecimal MAX_TRANSACTION_AMOUNT = new BigDecimal("999999999.99");

    /**
     * The whole balance-sign convention (see balanceDelta's doc comment) assumes amount is
     * always a non-negative magnitude, with direction encoded solely by the transaction's type.
     * A negative amount would silently double-invert that math -- e.g. an EXPENSE of -500 on a
     * savings account would ADD 500 to the balance instead of subtracting it. CSV imports are
     * already safe (CsvImportService.parseRow takes the absolute value), but nothing previously
     * stopped this from reaching the manual create/edit paths. The upper bound is SEC-13, added
     * alongside this method rather than as a separate check -- both are the same question (is this
     * a real transaction amount?), and splitting them across two validation layers (this one a
     * service-level ApiException, an upper bound as a Bean Validation annotation on the DTO) would
     * mean a reader has to check two places to know what "valid amount" means here.
     */
    private void requireAmountWithinBounds(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Amount must be greater than zero");
        }
        if (amount.compareTo(MAX_TRANSACTION_AMOUNT) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Amount can't exceed " + MAX_TRANSACTION_AMOUNT + " for a single transaction");
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
            requireAmountWithinBounds(req.amount());
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
     * "This is not a duplicate" -- the decision the engine cannot make and, until now, could only
     * be told during an import review.
     *
     * <h2>The gap this closes</h2>
     *
     * <p>{@code ReconciliationService}'s duplicate pass groups on account, date, amount and
     * description and flags every member but the earliest. It cannot distinguish "the same
     * statement uploaded twice" from "two metro fares on one day", which is exactly why
     * {@code notDuplicateConfirmedAt} exists -- and that field was writable from precisely one
     * place in the application, {@code ImportService.confirm}, reachable only from the import
     * review screen.
     *
     * <p>So a user who entered two identical transactions by hand had the second one flagged
     * {@code DUPLICATE} on the very next write, silently excluded from income, expenses, category
     * spend, budgets and every report, with no affordance anywhere to say otherwise. Worse, it was
     * excluded inconsistently: {@code Account.balance} counts it, because a duplicate-flagged row
     * is still a real ledger row. The ledger and the dashboard disagreed by that amount and the
     * user had no way to reconcile them.
     *
     * <p>Two identical same-day charges is not an exotic case -- it is a commute, a round of
     * coffees, a split bill paid twice.
     *
     * <h2>Why this also clears the pointer rather than only stamping the flag</h2>
     *
     * <p>{@code notDuplicateConfirmedAt} stops the NEXT pass re-flagging the row; on its own it
     * would leave the current {@code isDuplicateOf} and {@code DUPLICATE} status sitting there, so
     * the row would stay excluded until something else happened to touch it. The user asked for
     * this row to count, so it counts now.
     *
     * <p>Reconciliation re-runs afterwards for the same reason every other write path re-runs it:
     * a row returning to OK can complete or break a pattern elsewhere, and a third genuinely
     * accidental copy must still be flagged against this one.
     */
    @Transactional
    public TransactionDto confirmNotDuplicate(UUID userId, UUID txnId) {
        Transaction t = getOwned(userId, txnId);

        t.setNotDuplicateConfirmedAt(java.time.Instant.now());
        t.setIsDuplicateOf(null);
        t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
        t.setReconciliationExplanation(null);
        Transaction saved = transactionRepository.save(t);

        // The balance is deliberately NOT touched. A duplicate-flagged row was always counted in
        // Account.balance -- the flag only ever governed what the reports exclude -- so the money
        // does not move here. What changes is that the reports now agree with the balance, which
        // is the whole point.
        reconciliationService.reconcileForUser(userId);
        recurringService.detectForUser(userId);

        auditService.record(userId, "TRANSACTION_CONFIRMED_NOT_DUPLICATE", "Transaction", txnId,
                Map.of("amount", saved.getAmount(), "date", String.valueOf(saved.getTxnDate())));

        return TransactionDto.from(saved,
                categoryNamesById(userId).getOrDefault(saved.getCategoryId(), "Uncategorized"));
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
     *
     * Bug fix: this recorded TRANSACTION_CATEGORY_UPDATED with no actingAdminId at all. Its
     * self-service caller (MerchantController) has since been retired entirely -- per
     * AdminUserMerchantController's own doc comment, this is now the ONLY way anyone, including
     * the account's own owner, can apply a merchant-centric category choice -- so every single
     * call to this method is in fact an admin acting on a user's behalf, indistinguishable in the
     * audit trail from the user confirming their own category. Same bug class, same fix, as the
     * actorId threading already done for RelationshipService/MerchantService/RoleService/
     * RuleService/AccountService/this class's own delete().
     */
    @Transactional
    public TransactionDto confirmMerchantCategory(UUID userId, UUID merchantId, UUID txnId, UUID categoryId, UUID actingAdminId) {
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
                Map.of("previousCategoryId", previousCategoryId, "newCategory", category.getName(),
                        "actorId", actingAdminId.toString()));
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

    /**
     * Bug fix: this recorded TRANSACTION_DELETED against only the target user, with no
     * actingAdminId anywhere -- AdminTransactionController (support-assisted transaction deletion)
     * calls this exact same method with the target userId sourced from the path, so an admin
     * deleting a user's transaction was indistinguishable in the audit trail from the user deleting
     * their own. Same bug class, same fix, as the actorId threading already done for
     * RelationshipService/MerchantService/RoleService/RuleService/AccountService.
     */
    @Transactional
    public void delete(UUID userId, UUID txnId, UUID actingAdminId) {
        Transaction t = getOwned(userId, txnId);
        clearReconciliationPointersTo(List.of(t.getId()));
        adjustAccountBalance(t.getAccountId(), balanceOf(t).negate());
        transactionRepository.delete(t); // soft delete via @SQLDelete on the entity
        // Removing a transaction can break a recurring group's pattern (e.g. deleting one of
        // three regularly-spaced charges), same reasoning as the reconciliation re-run below.
        reconciliationService.reconcileForUser(userId);
        recurringService.detectForUser(userId);
        auditService.record(userId, "TRANSACTION_DELETED", "Transaction", txnId,
                Map.of("amount", t.getAmount(), "description", String.valueOf(t.getDescription()),
                        "actorId", actingAdminId.toString()));
    }

    @Transactional
    public void bulkDelete(UUID userId, List<UUID> ids) {
        List<Transaction> owned = getOwnedAll(userId, ids);
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
        java.util.Set<UUID> removed = new java.util.HashSet<>(removedIds);
        // BH-056: written once at the end rather than a save() per row. ReconciliationService made
        // exactly this change for exactly this reason -- Hibernate's configured batch_size and
        // order_updates can do nothing for writes issued one statement at a time -- and this
        // method, which runs on the same delete paths, kept the per-row form.
        //
        // A LinkedHashSet because one surviving row can be reached by more than one of the three
        // lookups (a transfer partner that is also a refund target), and Transaction has no
        // equals/hashCode override, so this de-duplicates by identity while keeping write order
        // deterministic -- the same reasoning ReconciliationService's own `dirty` set carries.
        //
        // `removed` is a Set rather than the original List: contains() ran per candidate row
        // against a list of up to 500 ids, three times over.
        java.util.Set<Transaction> dirty = new java.util.LinkedHashSet<>();
        for (Transaction t : transactionRepository.findByIsDuplicateOfIn(removedIds)) {
            if (removed.contains(t.getId())) continue;
            t.setIsDuplicateOf(null);
            t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
            dirty.add(t);
        }
        for (Transaction t : transactionRepository.findByTransferPairIdIn(removedIds)) {
            if (removed.contains(t.getId())) continue;
            t.setTransfer(false);
            t.setTransferPairId(null);
            t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
            dirty.add(t);
        }
        for (Transaction t : transactionRepository.findByRefundOfTransactionIdIn(removedIds)) {
            if (removed.contains(t.getId())) continue;
            t.setRefundOfTransactionId(null);
            t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
            dirty.add(t);
        }
        if (!dirty.isEmpty()) transactionRepository.saveAll(dirty);
    }

    /**
     * WI1A — the last synchronous batch learning path, moved onto the queue WI1 built.
     *
     * <p>This used to call {@code categorizationService.learn} inline, once per id, up to
     * {@code TransactionDto.MAX_BULK_IDS} (500) times inside this one transaction. That is the
     * import path's exact pre-WI1 shape and it carried the same defect: {@code
     * MerchantLearningService.confirm} does a check-then-act against
     * {@code UNIQUE(user_id, merchant_id, category_id)}, so one lost race threw a constraint
     * violation that poisoned this transaction and rolled back every one of the 500
     * recategorizations the user had just asked for — including the 499 that had nothing to do with
     * the merchant that lost.
     *
     * <p>{@code queueLearning} writes the event row in THIS transaction, so a bulk action that
     * fails for any other reason takes its queued learning with it, and defers only the applying.
     * See {@code CategorizationService.queueLearning} for why the boundary is drawn there and not
     * one line either side of it.
     *
     * <p>Single, interactive recategorization ({@link #updateCategory}, {@link #confirmMerchantCategory},
     * {@link #create}) deliberately stays synchronous — see {@code CategorizationService.learn}.
     */
    @Transactional
    public void bulkRecategorize(UUID userId, List<UUID> ids, String categoryName) {
        Category category = categorizationService.resolveOrCreateCategory(userId, categoryName);
        // BH-057: one query for the whole list rather than one per id -- see getOwnedAll.
        for (Transaction t : getOwnedAll(userId, ids)) {
            t.setCategoryId(category.getId());
            t.setNeedsCategoryReview(false); // an explicit bulk choice resolves the review flag too — see updateCategory()
            t.setCategoryManuallySet(true);
            t.setDecisionSource(Transaction.DecisionSource.MANUAL);
            t.setDecisionRuleId(null);
            categorizationService.queueLearning(userId, t.getDescription(), category.getId());
            transactionRepository.save(t);
        }
        auditService.record(userId, "TRANSACTION_BULK_RECATEGORIZED", "Transaction", null,
                Map.of("count", ids.size(), "newCategory", categoryName));
    }

    /**
     * BH-057. The owned rows for a whole bulk id list, in one query.
     *
     * <p>{@code bulkDelete} and {@code bulkRecategorize} called {@link #getOwned} per id -- up to
     * {@code TransactionDto.MAX_BULK_IDS} (500) {@code findById} round trips inside one
     * transaction, before the writes and before the two full-history reconciliation passes that
     * follow. The bound is correct and stays; the round trips were free to remove.
     *
     * <p>Per-id error semantics are preserved deliberately, which is why this re-walks {@code ids}
     * rather than just checking sizes: a caller who passes one id they do not own still gets the
     * 403 naming a transaction, and one that does not exist still gets a 404, exactly as before.
     * Collapsing both into "some of these are not yours" would be a worse answer cheaply obtained.
     */
    private List<Transaction> getOwnedAll(UUID userId, List<UUID> ids) {
        Map<UUID, Transaction> found = transactionRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Transaction::getId, t -> t));
        return ids.stream()
                .map(id -> OwnershipGuard.requireOwned(
                        java.util.Optional.ofNullable(found.get(id)),
                        Transaction::getUserId, userId, "Transaction"))
                .toList();
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
