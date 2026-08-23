package com.finora.transactions;

import com.finora.entity.Account;
import com.finora.entity.Category;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.AccountRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.service.AuditService;
import com.finora.service.BankManagementService;
import com.finora.service.CategorizationService;
import com.finora.service.ReconciliationService;
import com.finora.service.RecurringService;
import com.finora.service.ProviderType;
import com.finora.service.SmsProvider;
import com.finora.service.SmsResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TransactionServiceTest {

    private TransactionRepository transactionRepository;
    private CategoryRepository categoryRepository;
    private AccountRepository accountRepository;
    private CategorizationService categorizationService;
    private ReconciliationService reconciliationService;
    private RecurringService recurringService;
    private BankManagementService bankManagementService;
    private UserRepository userRepository;
    private SmsProvider smsProvider;
    private AuditService auditService;
    private TransactionService transactionService;

    private final UUID userId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();
    private Category dummyCategory;

    @BeforeEach
    void setUp() {
        transactionRepository = mock(TransactionRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        accountRepository = mock(AccountRepository.class);
        categorizationService = mock(CategorizationService.class);
        // Preserves every existing test's expectation (needsCategoryReview mirrors suggestion
        // source alone) by default; tests that specifically exercise the confidence-threshold
        // behaviour override this per-test.
        when(categorizationService.needsCategoryReview(any(), anyBoolean(), any()))
                .thenAnswer(inv -> inv.getArgument(1));
        reconciliationService = mock(ReconciliationService.class);
        recurringService = mock(RecurringService.class);
        // Only reached by search() when a keyword is supplied (bank-aware search) -- delegates to
        // the real static BankRegistry (same built-in-bank matching BankManagementService.search()
        // itself does when there are no custom banks involved, which none of these tests set up)
        // rather than a blanket "no matches" stub, so search_withAKeywordMatchingABankName... below
        // still sees "Punjab National Bank" resolve to PNB the way it did before this service took
        // on a BankManagementService dependency.
        bankManagementService = mock(BankManagementService.class);
        when(bankManagementService.search(any())).thenAnswer(invocation ->
                com.finora.util.BankRegistry.search(invocation.getArgument(0)).stream()
                        .map(com.finora.accounts.AccountDto.BankDto::from).toList());
        userRepository = mock(UserRepository.class);
        smsProvider = mock(SmsProvider.class);
        // doSendTransactionAlert() now records an audit entry off the SmsResult it gets back --
        // an unstubbed mock returns null, which would NPE the one test below that actually
        // reaches this call.
        when(smsProvider.sendTransactionAlert(any(), any(), any(), any()))
                .thenReturn(SmsResult.success(ProviderType.TWO_FACTOR, "test-message-id"));
        auditService = mock(AuditService.class);
        transactionService = new TransactionService(transactionRepository, categoryRepository, accountRepository,
                categorizationService, reconciliationService, recurringService, auditService,
                bankManagementService, userRepository, smsProvider);

        dummyCategory = new Category();
        ReflectionTestUtils.setField(dummyCategory, "id", UUID.randomUUID());
        dummyCategory.setUserId(userId);
        dummyCategory.setName("Dining");
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));
        // create() now verifies the request's accountId is owned by userId before doing anything
        // else (see TransactionService's own bug-fix comment on that check) -- most tests below
        // build their CreateRequest with a throwaway UUID.randomUUID() accountId and don't care
        // about account specifics, so this generic stub lets ownership resolve for any of them.
        // Tests that DO care what account.findById returns (balance-direction tests, mainly)
        // already register their own more specific when(accountRepository.findById(accountId))
        // stub for their own known id, which Mockito matches ahead of this any()-matcher fallback.
        when(accountRepository.findById(any())).thenReturn(Optional.of(account(UUID.randomUUID(), Account.Type.SAVINGS, BigDecimal.ZERO)));
        // BH-057: the bulk paths fetch their whole id list in one query now instead of one
        // findById per id. Every test below stubs findById, and none of them care HOW the rows are
        // fetched -- what they assert is that the review flag is cleared on each, that learning is
        // queued per row, that recurring detection runs once for the batch.
        //
        // So this answers findAllById by delegating to whatever findById is stubbed with, rather
        // than making each test restate its fixture in a second form. A test that had to be
        // rewritten because a service swapped one query shape for an equivalent one is a test
        // pinned to the implementation, and re-pinning it to the new implementation would just
        // move the problem.
        when(transactionRepository.findAllById(any())).thenAnswer(invocation -> {
            List<Transaction> found = new java.util.ArrayList<>();
            for (UUID id : invocation.<Iterable<UUID>>getArgument(0)) {
                transactionRepository.findById(id).ifPresent(found::add);
            }
            return found;
        });
    }

    private Transaction ownedTransaction(UUID id, UUID owner) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", id);
        t.setUserId(owner);
        t.setDescription("Some transaction");
        t.setAmount(BigDecimal.valueOf(100));
        t.setTxnType(Transaction.Type.EXPENSE);
        return t;
    }

    private Account account(UUID id, Account.Type type, BigDecimal balance) {
        Account a = new Account();
        ReflectionTestUtils.setField(a, "id", id);
        a.setUserId(userId);
        a.setName("Test Account");
        a.setAccountType(type);
        a.setBalance(balance);
        return a;
    }

    @Test
    void create_runsRecurringDetection_alongsideReconciliation() {
        // See docs/team-message-financial-intelligence-v1-closeout.md -- Transaction.recurring
        // must not depend on whether the user has ever opened the Recurring page.
        when(categorizationService.suggest(eq(userId), anyString(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Dining", "rule", UUID.randomUUID(), Transaction.DecisionSource.KEYWORD_MATCH, null));
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Dining"))).thenReturn(dummyCategory);

        var req = new TransactionDto.CreateRequest(UUID.randomUUID(), null, LocalDate.now(),
                "Swiggy order", BigDecimal.valueOf(486), "EXPENSE", List.of());

        transactionService.create(userId, req);

        verify(recurringService).detectForUser(userId);
    }

    private User userWithPhone(boolean phoneVerified) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setPhoneNumber("+919876543210");
        user.setPhoneVerified(phoneVerified);
        return user;
    }

    /** Real-time transaction alert SMS -- scoped to this manual-entry create() path only (see
     *  TransactionService.sendTransactionAlert's own doc comment for why bulk statement import
     *  never triggers this). */
    @Test
    void create_sendsATransactionAlertSms_whenThePhoneIsVerified() {
        when(categorizationService.suggest(eq(userId), anyString(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Dining", "rule", UUID.randomUUID(), Transaction.DecisionSource.KEYWORD_MATCH, null));
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Dining"))).thenReturn(dummyCategory);
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithPhone(true)));

        var req = new TransactionDto.CreateRequest(UUID.randomUUID(), null, LocalDate.now(),
                "Swiggy order", BigDecimal.valueOf(486), "EXPENSE", List.of());

        transactionService.create(userId, req);

        verify(smsProvider).sendTransactionAlert(eq("+919876543210"), eq("Swiggy order"), eq(BigDecimal.valueOf(486)), eq("EXPENSE"));
        verify(auditService).record(eq(userId), eq("SMS_SENT"), eq("User"), eq(userId),
                argThat(metadata -> "transaction_alert".equals(metadata.get("type")) && Boolean.TRUE.equals(metadata.get("success"))));
    }

    @Test
    void create_doesNotSendATransactionAlertSms_whenThePhoneIsNotVerified() {
        when(categorizationService.suggest(eq(userId), anyString(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Dining", "rule", UUID.randomUUID(), Transaction.DecisionSource.KEYWORD_MATCH, null));
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Dining"))).thenReturn(dummyCategory);
        when(userRepository.findById(userId)).thenReturn(Optional.of(userWithPhone(false)));

        var req = new TransactionDto.CreateRequest(UUID.randomUUID(), null, LocalDate.now(),
                "Swiggy order", BigDecimal.valueOf(486), "EXPENSE", List.of());

        transactionService.create(userId, req);

        verify(smsProvider, never()).sendTransactionAlert(any(), any(), any(), any());
    }

    @Test
    void create_appliesSideEffectRules_andUsesTheReturnedCategoryOverride_inTheResponse() {
        // A matching MARK_INVESTMENT rule overrides whatever category the primary suggestion
        // picked -- CategorizationService.applySideEffectRules returns the new Category, and
        // create() must use it for the response, not the pre-side-effect `category` variable.
        var suggestion = new CategorizationService.Suggestion("Shopping", "rule", UUID.randomUUID(), Transaction.DecisionSource.KEYWORD_MATCH, null);
        when(categorizationService.suggest(eq(userId), anyString(), any(), any())).thenReturn(suggestion);
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Shopping"))).thenReturn(dummyCategory);

        Category investments = new Category();
        ReflectionTestUtils.setField(investments, "id", UUID.randomUUID());
        investments.setUserId(userId);
        investments.setName("Investments");
        when(categorizationService.applySideEffectRules(eq(userId), any(Transaction.class))).thenReturn(investments);

        var req = new TransactionDto.CreateRequest(UUID.randomUUID(), null, LocalDate.now(),
                "SIP MUTUAL FUND DEDUCTION", BigDecimal.valueOf(5000), "EXPENSE", List.of());

        var result = transactionService.create(userId, req);

        assertThat(result.categoryName()).isEqualTo("Investments");
        verify(transactionRepository).save(argThat(t -> investments.getId().equals(t.getCategoryId())));
    }

    @Test
    void create_engineSuggestedAssignCategoryRuleMatch_recordsTheMatch() {
        // Financial Intelligence Workspace, Rule Management module -- create() is always a real
        // write (no staging step like CsvImportService), so it's safe to record right here. See
        // RuleEngineService.recordMatch's doc comment for why this ISN'T done inside suggest()
        // itself.
        UUID ruleId = UUID.randomUUID();
        var suggestion = new CategorizationService.Suggestion("Dining", "user_rule", UUID.randomUUID(), Transaction.DecisionSource.USER_RULE, ruleId);
        when(categorizationService.suggest(eq(userId), anyString(), any(), any())).thenReturn(suggestion);
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Dining"))).thenReturn(dummyCategory);

        var req = new TransactionDto.CreateRequest(UUID.randomUUID(), null, LocalDate.now(),
                "Swiggy order", BigDecimal.valueOf(486), "EXPENSE", List.of());

        transactionService.create(userId, req);

        verify(categorizationService).recordRuleMatch(ruleId);
    }

    @Test
    void create_noRuleMatch_recordsNothing() {
        var suggestion = new CategorizationService.Suggestion("Other", "default", UUID.randomUUID(), Transaction.DecisionSource.MERCHANT_DEFAULT, null);
        when(categorizationService.suggest(eq(userId), anyString(), any(), any())).thenReturn(suggestion);
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Other"))).thenReturn(dummyCategory);

        var req = new TransactionDto.CreateRequest(UUID.randomUUID(), null, LocalDate.now(),
                "Some unknown vendor", BigDecimal.valueOf(486), "EXPENSE", List.of());

        transactionService.create(userId, req);

        verify(categorizationService).recordRuleMatch(null);
    }

    @Test
    void create_sideEffectRulesReturnNull_keepsThePrimarySuggestionsCategory() {
        var suggestion = new CategorizationService.Suggestion("Dining", "rule", UUID.randomUUID(), Transaction.DecisionSource.KEYWORD_MATCH, null);
        when(categorizationService.suggest(eq(userId), anyString(), any(), any())).thenReturn(suggestion);
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Dining"))).thenReturn(dummyCategory);
        // applySideEffectRules unstubbed -- defaults to null, i.e. no side-effect rule matched.

        var req = new TransactionDto.CreateRequest(UUID.randomUUID(), null, LocalDate.now(),
                "Swiggy order", BigDecimal.valueOf(486), "EXPENSE", List.of());

        var result = transactionService.create(userId, req);

        assertThat(result.categoryName()).isEqualTo("Dining");
    }

    @Test
    void create_withExplicitCategory_resolvesMerchantAndLearnsFromIt_doesNotFlagForReview() {
        UUID merchantId = UUID.randomUUID();
        when(categorizationService.resolveMerchantId(eq(userId), anyString())).thenReturn(merchantId);
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Dining"))).thenReturn(dummyCategory);

        var req = new TransactionDto.CreateRequest(UUID.randomUUID(), "Dining", LocalDate.now(),
                "Swiggy order", BigDecimal.valueOf(486), "EXPENSE", List.of());

        var result = transactionService.create(userId, req);

        assertThat(result.categoryName()).isEqualTo("Dining");
        verify(categorizationService).learn(eq(userId), eq("Swiggy order"), eq(dummyCategory.getId()));
        verify(transactionRepository).save(argThat(t -> merchantId.equals(t.getMerchantId()) && !t.isNeedsCategoryReview()));
    }

    @Test
    void create_withNoExplicitCategory_usesEngineSuggestion_andFlagsForReviewWhenSourceIsDefault() {
        UUID merchantId = UUID.randomUUID();
        var suggestion = new CategorizationService.Suggestion("Other", "default", merchantId, Transaction.DecisionSource.MERCHANT_DEFAULT, null);
        when(categorizationService.suggest(eq(userId), anyString(), any(), any())).thenReturn(suggestion);
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Other"))).thenReturn(dummyCategory);

        var req = new TransactionDto.CreateRequest(UUID.randomUUID(), null, LocalDate.now(),
                "Unknown merchant", BigDecimal.valueOf(50), "EXPENSE", List.of());

        transactionService.create(userId, req);

        // A "default" (no confident guess) suggestion must never be learned from — that would
        // teach the merchant map a non-decision, same bug class fixed in CsvImportService.
        verify(categorizationService, never()).learn(any(), any(), any());
        verify(transactionRepository).save(argThat(t -> t.isNeedsCategoryReview() && merchantId.equals(t.getMerchantId())));
    }

    @Test
    void create_setsDecisionConfidence_fromTheSuggestion() {
        var suggestion = new CategorizationService.Suggestion("Dining", "rule", UUID.randomUUID(),
                Transaction.DecisionSource.KEYWORD_MATCH, null, 70);
        when(categorizationService.suggest(eq(userId), anyString(), any(), any())).thenReturn(suggestion);
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Dining"))).thenReturn(dummyCategory);

        var req = new TransactionDto.CreateRequest(UUID.randomUUID(), null, LocalDate.now(),
                "Swiggy order", BigDecimal.valueOf(486), "EXPENSE", List.of());

        transactionService.create(userId, req);

        verify(transactionRepository).save(argThat(t -> Integer.valueOf(70).equals(t.getDecisionConfidence())));
    }

    @Test
    void create_honorsCategorizationServicesNeedsCategoryReviewDecision_notJustSourceEqualsDefault() {
        UUID merchantId = UUID.randomUUID();
        var suggestion = new CategorizationService.Suggestion("Other", "default", merchantId,
                Transaction.DecisionSource.MERCHANT_DEFAULT, null, 20);
        when(categorizationService.suggest(eq(userId), anyString(), any(), any())).thenReturn(suggestion);
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Other"))).thenReturn(dummyCategory);
        // Overrides the setUp() default: this user's threshold is permissive enough that a 20%
        // default guess should NOT be flagged.
        when(categorizationService.needsCategoryReview(userId, true, 20)).thenReturn(false);

        var req = new TransactionDto.CreateRequest(UUID.randomUUID(), null, LocalDate.now(),
                "Unknown merchant", BigDecimal.valueOf(50), "EXPENSE", List.of());

        transactionService.create(userId, req);

        verify(transactionRepository).save(argThat(t -> !t.isNeedsCategoryReview()));
    }

    @Test
    void create_withNoExplicitCategory_doesNotFlagForReview_whenSuggestionSourceIsRule() {
        var suggestion = new CategorizationService.Suggestion("Dining", "rule", UUID.randomUUID(), Transaction.DecisionSource.KEYWORD_MATCH, null);
        when(categorizationService.suggest(eq(userId), anyString(), any(), any())).thenReturn(suggestion);
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Dining"))).thenReturn(dummyCategory);

        var req = new TransactionDto.CreateRequest(UUID.randomUUID(), null, LocalDate.now(),
                "Swiggy order", BigDecimal.valueOf(486), "EXPENSE", List.of());

        transactionService.create(userId, req);

        verify(transactionRepository).save(argThat(t -> !t.isNeedsCategoryReview()));
    }

    @Test
    void updateCategory_clearsReviewFlag_evenWhenChoosingOther() {
        UUID txnId = UUID.randomUUID();
        Transaction existing = ownedTransaction(txnId, userId);
        existing.setNeedsCategoryReview(true);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(existing));
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Other"))).thenReturn(dummyCategory);

        transactionService.updateCategory(userId, txnId, "Other");

        assertThat(existing.isNeedsCategoryReview()).isFalse();
        verify(categorizationService).learn(eq(userId), anyString(), any());
    }

    @Test
    void bulkRecategorize_clearsReviewFlagOnEveryTransaction() {
        // Regression test for a real bug: bulkRecategorize() originally didn't clear
        // needsCategoryReview, unlike the single-transaction updateCategory() path — a
        // transaction flagged for review would still show up in the Ask Once queue even
        // after being explicitly bulk-recategorized.
        UUID txn1Id = UUID.randomUUID();
        UUID txn2Id = UUID.randomUUID();
        Transaction t1 = ownedTransaction(txn1Id, userId);
        Transaction t2 = ownedTransaction(txn2Id, userId);
        t1.setNeedsCategoryReview(true);
        t2.setNeedsCategoryReview(true);
        when(transactionRepository.findById(txn1Id)).thenReturn(Optional.of(t1));
        when(transactionRepository.findById(txn2Id)).thenReturn(Optional.of(t2));
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Groceries"))).thenReturn(dummyCategory);

        transactionService.bulkRecategorize(userId, List.of(txn1Id, txn2Id), "Groceries", userId);

        assertThat(t1.isNeedsCategoryReview()).isFalse();
        assertThat(t2.isNeedsCategoryReview()).isFalse();
    }

    @Test
    void delete_throwsForbidden_whenTransactionBelongsToAnotherUser() {
        UUID txnId = UUID.randomUUID();
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(ownedTransaction(txnId, otherUserId)));

        assertThatThrownBy(() -> transactionService.delete(userId, txnId, userId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("does not belong to you");
    }

    @Test
    void delete_throwsNotFound_whenTransactionDoesNotExist() {
        UUID txnId = UUID.randomUUID();
        when(transactionRepository.findById(txnId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.delete(userId, txnId, userId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void delete_ownedTransaction_callsRepositoryDelete() {
        UUID txnId = UUID.randomUUID();
        Transaction t = ownedTransaction(txnId, userId);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));

        transactionService.delete(userId, txnId, userId);

        verify(transactionRepository).delete(t);
    }

    @Test
    void delete_runsRecurringDetection_alongsideReconciliation() {
        // Removing a transaction can break a recurring group's pattern -- see
        // docs/team-message-financial-intelligence-v1-closeout.md.
        UUID txnId = UUID.randomUUID();
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(ownedTransaction(txnId, userId)));

        transactionService.delete(userId, txnId, userId);

        verify(recurringService).detectForUser(userId);
    }

    // Bug fix: delete() used to record TRANSACTION_DELETED with no actingAdminId at all --
    // AdminTransactionController (support-assisted transaction deletion) calls this exact same
    // method, so an admin deleting a user's transaction was indistinguishable in the audit trail
    // from the user deleting their own. Same "actorId" convention as RelationshipService/
    // MerchantService/RoleService/RuleService/AccountService.
    @Test
    void delete_recordsActingAdminIdInAuditMetadata() {
        UUID txnId = UUID.randomUUID();
        UUID actingAdminId = UUID.randomUUID();
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(ownedTransaction(txnId, userId)));

        transactionService.delete(userId, txnId, actingAdminId);

        verify(auditService).record(eq(userId), eq("TRANSACTION_DELETED"), eq("Transaction"), eq(txnId),
                argThat(metadata -> actingAdminId.toString().equals(metadata.get("actorId"))));
    }

    @Test
    void bulkDelete_runsRecurringDetection_onceForTheWholeBatch() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        when(transactionRepository.findById(id1)).thenReturn(Optional.of(ownedTransaction(id1, userId)));
        when(transactionRepository.findById(id2)).thenReturn(Optional.of(ownedTransaction(id2, userId)));

        transactionService.bulkDelete(userId, List.of(id1, id2), userId);

        verify(recurringService, times(1)).detectForUser(userId);
    }

    // Bug 36: bulkDelete/bulkRecategorize recorded no actorId at all, unlike delete() above --
    // the higher-impact operation of the pair had weaker attribution than the lower-impact one.
    @Test
    void bulkDelete_recordsActingAdminIdInAuditMetadata() {
        UUID id1 = UUID.randomUUID();
        UUID actingAdminId = UUID.randomUUID();
        when(transactionRepository.findById(id1)).thenReturn(Optional.of(ownedTransaction(id1, userId)));

        transactionService.bulkDelete(userId, List.of(id1), actingAdminId);

        verify(auditService).record(eq(userId), eq("TRANSACTION_BULK_DELETED"), eq("Transaction"), eq(null),
                argThat(metadata -> actingAdminId.toString().equals(metadata.get("actorId"))));
    }

    @Test
    void bulkRecategorize_recordsActingAdminIdInAuditMetadata() {
        UUID txnId = UUID.randomUUID();
        UUID actingAdminId = UUID.randomUUID();
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(ownedTransaction(txnId, userId)));
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Groceries"))).thenReturn(dummyCategory);

        transactionService.bulkRecategorize(userId, List.of(txnId), "Groceries", actingAdminId);

        verify(auditService).record(eq(userId), eq("TRANSACTION_BULK_RECATEGORIZED"), eq("Transaction"), eq(null),
                argThat(metadata -> actingAdminId.toString().equals(metadata.get("actorId"))));
    }

    @Test
    void update_runsRecurringDetection_alongsideReconciliation() {
        UUID txnId = UUID.randomUUID();
        Transaction existing = ownedTransaction(txnId, userId);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(existing));

        var req = new TransactionDto.UpdateRequest(null, null, null, BigDecimal.valueOf(200), null, null, null, null);
        transactionService.update(userId, txnId, req);

        verify(recurringService).detectForUser(userId);
    }

    @Test
    void bulkDelete_stopsAtFirstTransactionNotOwnedByCaller() {
        UUID ownedId = UUID.randomUUID();
        UUID notOwnedId = UUID.randomUUID();
        when(transactionRepository.findById(ownedId)).thenReturn(Optional.of(ownedTransaction(ownedId, userId)));
        when(transactionRepository.findById(notOwnedId)).thenReturn(Optional.of(ownedTransaction(notOwnedId, otherUserId)));

        // The STATUS, not merely that something was thrown. BH-057 moved this path from a findById
        // per id to one bulk fetch, and a bulk fetch that came back empty would also throw here --
        // as 404, for a row that exists and belongs to someone else. Asserting the class alone
        // cannot tell the two apart, and "not found" for another user's transaction is both the
        // wrong answer and a worse one.
        assertThatThrownBy(() -> transactionService.bulkDelete(userId, List.of(ownedId, notOwnedId), userId))
                .isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).getStatus())
                        .isEqualTo(org.springframework.http.HttpStatus.FORBIDDEN));
    }

    // --- Account balance maintenance (previously: Account.balance was never touched by any
    // transaction create/edit/delete path — see TransactionService.balanceDelta/adjustAccountBalance) ---

    @Test
    void create_income_addsToASavingsAccountsBalance() {
        UUID accountId = UUID.randomUUID();
        Account acct = account(accountId, Account.Type.SAVINGS, BigDecimal.valueOf(1000));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(acct));
        var suggestion = new CategorizationService.Suggestion("Salary", "rule", UUID.randomUUID(), Transaction.DecisionSource.KEYWORD_MATCH, null);
        when(categorizationService.suggest(eq(userId), anyString(), any(), any())).thenReturn(suggestion);
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Salary"))).thenReturn(dummyCategory);

        var req = new TransactionDto.CreateRequest(accountId, null, LocalDate.now(), "Salary Credit",
                BigDecimal.valueOf(500), "INCOME", List.of());
        transactionService.create(userId, req);

        assertThat(acct.getBalance()).isEqualByComparingTo("1500");
    }

    @Test
    void create_expense_increasesCreditCardBalance_becauseBalanceRepresentsAmountOwed() {
        UUID accountId = UUID.randomUUID();
        Account acct = account(accountId, Account.Type.CREDIT_CARD, BigDecimal.valueOf(2000));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(acct));
        var suggestion = new CategorizationService.Suggestion("Shopping", "rule", UUID.randomUUID(), Transaction.DecisionSource.KEYWORD_MATCH, null);
        when(categorizationService.suggest(eq(userId), anyString(), any(), any())).thenReturn(suggestion);
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Shopping"))).thenReturn(dummyCategory);

        var req = new TransactionDto.CreateRequest(accountId, null, LocalDate.now(), "Amazon purchase",
                BigDecimal.valueOf(300), "EXPENSE", List.of());
        transactionService.create(userId, req);

        // A card purchase increases what's owed, not decreases it — the opposite direction from
        // a savings account's plain ledger convention.
        assertThat(acct.getBalance()).isEqualByComparingTo("2300");
    }

    @Test
    void create_withExplicitCategory_marksCategoryAsManuallySet() {
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Dining"))).thenReturn(dummyCategory);
        var req = new TransactionDto.CreateRequest(UUID.randomUUID(), "Dining", LocalDate.now(),
                "Swiggy order", BigDecimal.valueOf(486), "EXPENSE", List.of());

        transactionService.create(userId, req);

        verify(transactionRepository).save(argThat(Transaction::isCategoryManuallySet));
    }

    @Test
    void create_withEngineSuggestion_leavesCategoryAsAutomaticallyAssigned() {
        var suggestion = new CategorizationService.Suggestion("Dining", "rule", UUID.randomUUID(), Transaction.DecisionSource.KEYWORD_MATCH, null);
        when(categorizationService.suggest(eq(userId), anyString(), any(), any())).thenReturn(suggestion);
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Dining"))).thenReturn(dummyCategory);
        var req = new TransactionDto.CreateRequest(UUID.randomUUID(), null, LocalDate.now(),
                "Swiggy order", BigDecimal.valueOf(486), "EXPENSE", List.of());

        transactionService.create(userId, req);

        verify(transactionRepository).save(argThat(t -> !t.isCategoryManuallySet()));
    }

    @Test
    void update_changingAmountAndType_adjustsAccountBalanceByTheDifference() {
        UUID txnId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Transaction existing = ownedTransaction(txnId, userId);
        existing.setAccountId(accountId);
        existing.setAmount(BigDecimal.valueOf(100));
        existing.setTxnType(Transaction.Type.EXPENSE);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(existing));

        Account acct = account(accountId, Account.Type.SAVINGS, BigDecimal.valueOf(1000));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(acct));

        // Old: EXPENSE 100 (-100 contribution). New: INCOME 250 (+250 contribution).
        // Net effect of the edit itself is +350 on top of whatever the balance already was.
        var req = new TransactionDto.UpdateRequest(null, null, null, BigDecimal.valueOf(250), "INCOME",
                null, null, null);
        transactionService.update(userId, txnId, req);

        assertThat(acct.getBalance()).isEqualByComparingTo("1350");
        assertThat(existing.getAmount()).isEqualByComparingTo("250");
        assertThat(existing.getTxnType()).isEqualTo(Transaction.Type.INCOME);
    }

    @Test
    void update_withCategoryName_marksManuallySetAndClearsReviewFlag() {
        UUID txnId = UUID.randomUUID();
        Transaction existing = ownedTransaction(txnId, userId);
        existing.setNeedsCategoryReview(true);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(existing));
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Groceries"))).thenReturn(dummyCategory);

        var req = new TransactionDto.UpdateRequest(null, null, null, null, null, "Groceries", null, null);
        transactionService.update(userId, txnId, req);

        assertThat(existing.isCategoryManuallySet()).isTrue();
        assertThat(existing.isNeedsCategoryReview()).isFalse();
    }

    @Test
    void update_editsMerchantDescriptionNotesAndTags_withoutTouchingCategory() {
        UUID txnId = UUID.randomUUID();
        Transaction existing = ownedTransaction(txnId, userId);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(existing));

        var req = new TransactionDto.UpdateRequest(null, "Corrected description", "Corrected Merchant",
                null, null, null, "Reimbursed by roommate", List.of("shared"));
        transactionService.update(userId, txnId, req);

        assertThat(existing.getDescription()).isEqualTo("Corrected description");
        assertThat(existing.getMerchant()).isEqualTo("Corrected Merchant");
        assertThat(existing.getNotes()).isEqualTo("Reimbursed by roommate");
        assertThat(existing.getTags()).containsExactly("shared");
        verify(categorizationService, never()).resolveOrCreateCategory(any(), any());
    }

    @Test
    void updateCategory_marksCategoryAsManuallySet() {
        UUID txnId = UUID.randomUUID();
        Transaction existing = ownedTransaction(txnId, userId);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(existing));
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Dining"))).thenReturn(dummyCategory);

        transactionService.updateCategory(userId, txnId, "Dining");

        assertThat(existing.isCategoryManuallySet()).isTrue();
    }

    // --- confirmMerchantCategory (spec §5.5) ---

    @Test
    void confirmMerchantCategory_updatesCategoryAndLearnsAgainstTheMerchant() {
        UUID txnId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        Transaction existing = ownedTransaction(txnId, userId);
        existing.setMerchantId(merchantId);
        existing.setNeedsCategoryReview(true);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(existing));
        when(categoryRepository.findById(dummyCategory.getId())).thenReturn(Optional.of(dummyCategory));

        transactionService.confirmMerchantCategory(userId, merchantId, txnId, dummyCategory.getId(), userId);

        assertThat(existing.getCategoryId()).isEqualTo(dummyCategory.getId());
        assertThat(existing.isCategoryManuallySet()).isTrue();
        assertThat(existing.isNeedsCategoryReview()).isFalse();
        assertThat(existing.getDecisionSource()).isEqualTo(Transaction.DecisionSource.MANUAL);
        assertThat(existing.getDecisionRuleId()).isNull();
        // categorizationService.learn() is what actually resolves the merchant and calls
        // MerchantLearningService.confirm() internally -- see CategorizationService.learn()'s
        // own doc comment. This is the assertion that ties confirm-category to real merchant
        // learning, not just a category-field update.
        verify(categorizationService).learn(eq(userId), eq(existing.getDescription()), eq(dummyCategory.getId()));
    }

    @Test
    void confirmMerchantCategory_rejectsWhenTransactionsMerchantDoesNotMatchThePathMerchant() {
        UUID txnId = UUID.randomUUID();
        UUID actualMerchantId = UUID.randomUUID();
        UUID wrongMerchantId = UUID.randomUUID();
        Transaction existing = ownedTransaction(txnId, userId);
        existing.setMerchantId(actualMerchantId);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> transactionService.confirmMerchantCategory(userId, wrongMerchantId, txnId, dummyCategory.getId(), userId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("isn't resolved to the given merchant");

        verify(categorizationService, never()).learn(any(), any(), any());
    }

    @Test
    void confirmMerchantCategory_rejectsATransactionWithNoResolvedMerchantAtAll() {
        UUID txnId = UUID.randomUUID();
        Transaction existing = ownedTransaction(txnId, userId); // merchantId left null
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> transactionService.confirmMerchantCategory(userId, UUID.randomUUID(), txnId, dummyCategory.getId(), userId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("isn't resolved to the given merchant");
    }

    @Test
    void confirmMerchantCategory_rejectsACategoryIdThatDoesNotExist() {
        UUID txnId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        Transaction existing = ownedTransaction(txnId, userId);
        existing.setMerchantId(merchantId);
        UUID bogusCategoryId = UUID.randomUUID();
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(existing));
        when(categoryRepository.findById(bogusCategoryId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.confirmMerchantCategory(userId, merchantId, txnId, bogusCategoryId, userId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Category not found");
    }

    @Test
    void confirmMerchantCategory_rejectsACategoryBelongingToAnotherUser() {
        UUID txnId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        Transaction existing = ownedTransaction(txnId, userId);
        existing.setMerchantId(merchantId);

        Category othersCategory = new Category();
        ReflectionTestUtils.setField(othersCategory, "id", UUID.randomUUID());
        othersCategory.setUserId(otherUserId);
        othersCategory.setName("Someone Else's Category");

        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(existing));
        when(categoryRepository.findById(othersCategory.getId())).thenReturn(Optional.of(othersCategory));

        assertThatThrownBy(() -> transactionService.confirmMerchantCategory(userId, merchantId, txnId, othersCategory.getId(), userId))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Category not found");
    }

    // Bug fix: confirmMerchantCategory() used to record TRANSACTION_CATEGORY_UPDATED with no
    // actingAdminId at all. Its self-service caller (MerchantController) has since been retired
    // entirely, so AdminUserMerchantController is now the ONLY way anyone -- including the
    // account's own owner -- can reach this method, making every call an admin acting on a user's
    // behalf. Same "actorId" convention as this class's own delete() and the other services
    // audited in this pass.
    @Test
    void confirmMerchantCategory_recordsActingAdminIdInAuditMetadata() {
        UUID txnId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        UUID actingAdminId = UUID.randomUUID();
        Transaction existing = ownedTransaction(txnId, userId);
        existing.setMerchantId(merchantId);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(existing));
        when(categoryRepository.findById(dummyCategory.getId())).thenReturn(Optional.of(dummyCategory));

        transactionService.confirmMerchantCategory(userId, merchantId, txnId, dummyCategory.getId(), actingAdminId);

        verify(auditService).record(eq(userId), eq("TRANSACTION_CATEGORY_UPDATED"), eq("Transaction"), eq(txnId),
                argThat(metadata -> actingAdminId.toString().equals(metadata.get("actorId"))));
    }

    @Test
    void bulkRecategorize_marksCategoryAsManuallySetOnEveryTransaction() {
        UUID txn1Id = UUID.randomUUID();
        Transaction t1 = ownedTransaction(txn1Id, userId);
        when(transactionRepository.findById(txn1Id)).thenReturn(Optional.of(t1));
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Groceries"))).thenReturn(dummyCategory);

        transactionService.bulkRecategorize(userId, List.of(txn1Id), "Groceries", userId);

        assertThat(t1.isCategoryManuallySet()).isTrue();
    }

    // --- WI1A: which learning path each action takes -------------------------------------------
    // The wiring, asserted here; what it BUYS (a lost race no longer discards the whole batch) is
    // asserted in BulkRecategorizeLearningIT against a real transaction, because a mocked test of
    // that property would pass against code that does not have it.

    @Test
    void bulkRecategorize_queuesLearningForEveryRow_ratherThanApplyingItInline() {
        UUID txn1Id = UUID.randomUUID();
        UUID txn2Id = UUID.randomUUID();
        Transaction t1 = ownedTransaction(txn1Id, userId);
        Transaction t2 = ownedTransaction(txn2Id, userId);
        t1.setDescription("SWIGGY*ORDR9182 BLR");
        t2.setDescription("SWIGGY*ORDR7710 BLR");
        when(transactionRepository.findById(txn1Id)).thenReturn(Optional.of(t1));
        when(transactionRepository.findById(txn2Id)).thenReturn(Optional.of(t2));
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Groceries"))).thenReturn(dummyCategory);

        transactionService.bulkRecategorize(userId, List.of(txn1Id, txn2Id), "Groceries", userId);

        verify(categorizationService).queueLearning(userId, "SWIGGY*ORDR9182 BLR", dummyCategory.getId());
        verify(categorizationService).queueLearning(userId, "SWIGGY*ORDR7710 BLR", dummyCategory.getId());
        // One event per row, not one per distinct merchant: each row is a real confirmation and
        // increments confirmation_count once, which is what ConfidenceEngine.topCategory weighs.
        verify(categorizationService, times(2)).queueLearning(any(), any(), any());
        // The synchronous path is what this work item removed from here.
        verify(categorizationService, never()).learn(any(), any(), any());
    }

    /**
     * The other half of the WI1A rule, and the one a future change is more likely to get wrong:
     * a SINGLE interactive action still learns synchronously. The caller is waiting on the result,
     * the blast radius of a failure is the one change they asked for, and an error they can see and
     * retry beats a silent queue entry — see CategorizationService.learn's doc comment.
     */
    @Test
    void singleInteractiveRecategorizationStillLearnsSynchronously_andNeverQueues() {
        UUID txnId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();

        Transaction viaUpdateCategory = ownedTransaction(txnId, userId);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(viaUpdateCategory));
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Dining"))).thenReturn(dummyCategory);
        transactionService.updateCategory(userId, txnId, "Dining");

        UUID confirmTxnId = UUID.randomUUID();
        Transaction viaConfirm = ownedTransaction(confirmTxnId, userId);
        viaConfirm.setMerchantId(merchantId);
        when(transactionRepository.findById(confirmTxnId)).thenReturn(Optional.of(viaConfirm));
        when(categoryRepository.findById(dummyCategory.getId())).thenReturn(Optional.of(dummyCategory));
        transactionService.confirmMerchantCategory(userId, merchantId, confirmTxnId, dummyCategory.getId(), userId);

        var createReq = new TransactionDto.CreateRequest(UUID.randomUUID(), "Dining", LocalDate.now(),
                "Swiggy order", BigDecimal.valueOf(486), "EXPENSE", List.of());
        transactionService.create(userId, createReq);

        verify(categorizationService, times(3)).learn(eq(userId), anyString(), eq(dummyCategory.getId()));
        verify(categorizationService, never()).queueLearning(any(), any(), any());
    }

    @Test
    void delete_reversesTheAccountBalanceContribution() {
        UUID txnId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Transaction t = ownedTransaction(txnId, userId);
        t.setAccountId(accountId);
        t.setAmount(BigDecimal.valueOf(200));
        t.setTxnType(Transaction.Type.INCOME);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));

        Account acct = account(accountId, Account.Type.SAVINGS, BigDecimal.valueOf(1000));
        when(accountRepository.findById(accountId)).thenReturn(Optional.of(acct));

        transactionService.delete(userId, txnId, userId);

        // The 200 of income this transaction contributed is reversed out on delete.
        assertThat(acct.getBalance()).isEqualByComparingTo("800");
    }

    @Test
    void delete_clearsDuplicateAndTransferPointers_onSurvivingTransactions() {
        // Regression test: unlike StatementImportService.delete() (whole-statement delete),
        // this single-transaction delete() never used to clean up surviving transactions that
        // pointed at the deleted row via isDuplicateOf/transferPairId — they'd keep showing
        // DUPLICATE/TRANSFER status while referencing a row that no longer visibly exists.
        UUID txnId = UUID.randomUUID();
        Transaction t = ownedTransaction(txnId, userId);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(t));

        UUID dupPartnerId = UUID.randomUUID();
        Transaction dupPartner = ownedTransaction(dupPartnerId, userId);
        dupPartner.setIsDuplicateOf(txnId);
        dupPartner.setReconciliationStatus(Transaction.ReconciliationStatus.DUPLICATE);
        when(transactionRepository.findByIsDuplicateOfIn(List.of(txnId))).thenReturn(List.of(dupPartner));

        UUID transferPartnerId = UUID.randomUUID();
        Transaction transferPartner = ownedTransaction(transferPartnerId, userId);
        transferPartner.setTransfer(true);
        transferPartner.setTransferPairId(txnId);
        transferPartner.setReconciliationStatus(Transaction.ReconciliationStatus.TRANSFER);
        when(transactionRepository.findByTransferPairIdIn(List.of(txnId))).thenReturn(List.of(transferPartner));

        transactionService.delete(userId, txnId, userId);

        assertThat(dupPartner.getIsDuplicateOf()).isNull();
        assertThat(dupPartner.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.OK);
        assertThat(transferPartner.isTransfer()).isFalse();
        assertThat(transferPartner.getTransferPairId()).isNull();
        assertThat(transferPartner.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.OK);
    }

    @Test
    void delete_clearsRefundPointer_onSurvivingTransaction() {
        // Regression test for a real bug: clearReconciliationPointersTo() cleaned up
        // isDuplicateOf/transferPairId pointers but never refundOfTransactionId. Deleting the
        // EXPENSE side of a matched refund pair (see ReconciliationService's refund pass) left
        // the surviving INCOME row's refundOfTransactionId dangling and permanently stuck at
        // ReconciliationStatus.REFUND -- with no reconciliation pass ever re-validating an
        // existing REFUND match, that income stayed silently excluded from DashboardService's
        // totals forever, with no way to self-correct.
        UUID expenseId = UUID.randomUUID();
        Transaction expense = ownedTransaction(expenseId, userId);
        when(transactionRepository.findById(expenseId)).thenReturn(Optional.of(expense));

        UUID refundIncomeId = UUID.randomUUID();
        Transaction refundIncome = ownedTransaction(refundIncomeId, userId);
        refundIncome.setTxnType(Transaction.Type.INCOME);
        refundIncome.setRefundOfTransactionId(expenseId);
        refundIncome.setReconciliationStatus(Transaction.ReconciliationStatus.REFUND);
        when(transactionRepository.findByRefundOfTransactionIdIn(List.of(expenseId))).thenReturn(List.of(refundIncome));

        transactionService.delete(userId, expenseId, userId);

        assertThat(refundIncome.getRefundOfTransactionId()).isNull();
        assertThat(refundIncome.getReconciliationStatus()).isEqualTo(Transaction.ReconciliationStatus.OK);
    }

    // --- Amount validation (previously: neither create() nor update() checked that amount was
    // positive, even though the whole balance-sign convention assumes amount is always a
    // non-negative magnitude with direction encoded solely by type -- a negative amount would
    // silently double-invert the balance math in balanceDelta()) ---

    @Test
    void create_rejectsZeroOrNegativeAmount() {
        var zeroReq = new TransactionDto.CreateRequest(UUID.randomUUID(), "Dining", LocalDate.now(),
                "Swiggy order", BigDecimal.ZERO, "EXPENSE", List.of());
        assertThatThrownBy(() -> transactionService.create(userId, zeroReq))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("greater than zero");

        var negativeReq = new TransactionDto.CreateRequest(UUID.randomUUID(), "Dining", LocalDate.now(),
                "Swiggy order", BigDecimal.valueOf(-50), "EXPENSE", List.of());
        assertThatThrownBy(() -> transactionService.create(userId, negativeReq))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("greater than zero");

        verify(transactionRepository, never()).save(any());
    }

    // SEC-13 (docs/quality/bug-reports/2026-08-19-security-review-findings.md): amount had a floor
    // (above) but no ceiling -- the NUMERIC(14,2) column was the only backstop, surfaced as a raw
    // 409 rather than a message naming the field.
    @Test
    void create_rejectsAnAmountAboveTheSanityCeiling() {
        var req = new TransactionDto.CreateRequest(UUID.randomUUID(), "Dining", LocalDate.now(),
                "Suspiciously large charge", new BigDecimal("1000000000.00"), "EXPENSE", List.of());

        assertThatThrownBy(() -> transactionService.create(userId, req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("can't exceed");

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void create_acceptsAnAmountExactlyAtTheSanityCeiling() {
        when(categorizationService.suggest(eq(userId), anyString(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Dining", "rule", UUID.randomUUID(), Transaction.DecisionSource.KEYWORD_MATCH, null));
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Dining"))).thenReturn(dummyCategory);

        var req = new TransactionDto.CreateRequest(UUID.randomUUID(), null, LocalDate.now(),
                "Large but real charge", new BigDecimal("999999999.99"), "EXPENSE", List.of());

        transactionService.create(userId, req);

        verify(transactionRepository).save(any());
    }

    // SEC-06 (docs/quality/bug-reports/2026-08-19-security-review-findings.md): a double-click or a
    // retried POST with no idempotency key created two rows and moved the account balance twice.
    @Test
    void create_withNoIdempotencyKey_behavesExactlyAsBefore_creatingANewTransactionEveryCall() {
        when(categorizationService.suggest(eq(userId), anyString(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Dining", "rule", UUID.randomUUID(), Transaction.DecisionSource.KEYWORD_MATCH, null));
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Dining"))).thenReturn(dummyCategory);

        var req = new TransactionDto.CreateRequest(UUID.randomUUID(), null, LocalDate.now(),
                "Swiggy order", BigDecimal.valueOf(486), "EXPENSE", List.of());

        transactionService.create(userId, req);
        transactionService.create(userId, req);

        verify(transactionRepository, times(2)).save(any());
        verify(transactionRepository, never()).findByUserIdAndIdempotencyKey(any(), any());
    }

    @Test
    void create_withAnUnseenIdempotencyKey_createsANewTransactionAndStampsTheKey() {
        when(categorizationService.suggest(eq(userId), anyString(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Dining", "rule", UUID.randomUUID(), Transaction.DecisionSource.KEYWORD_MATCH, null));
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Dining"))).thenReturn(dummyCategory);
        when(transactionRepository.findByUserIdAndIdempotencyKey(userId, "client-key-1")).thenReturn(Optional.empty());

        var req = new TransactionDto.CreateRequest(UUID.randomUUID(), null, LocalDate.now(),
                "Swiggy order", BigDecimal.valueOf(486), "EXPENSE", List.of(), "client-key-1");

        transactionService.create(userId, req);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getIdempotencyKey()).isEqualTo("client-key-1");
    }

    @Test
    void create_withAPreviouslySeenIdempotencyKey_returnsTheOriginalTransaction_withoutInsertingOrMovingBalanceAgain() {
        UUID existingId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        Transaction existing = new Transaction();
        ReflectionTestUtils.setField(existing, "id", existingId);
        existing.setUserId(userId);
        existing.setAccountId(accountId);
        existing.setDescription("Swiggy order");
        existing.setAmount(BigDecimal.valueOf(486));
        existing.setTxnType(Transaction.Type.EXPENSE);
        existing.setTxnDate(LocalDate.now());
        existing.setCategoryId(dummyCategory.getId());
        existing.setIdempotencyKey("client-key-1");
        when(transactionRepository.findByUserIdAndIdempotencyKey(userId, "client-key-1"))
                .thenReturn(Optional.of(existing));
        when(categoryRepository.findById(dummyCategory.getId())).thenReturn(Optional.of(dummyCategory));

        var req = new TransactionDto.CreateRequest(accountId, null, existing.getTxnDate(),
                "Swiggy order", BigDecimal.valueOf(486), "EXPENSE", List.of(), "client-key-1");

        TransactionDto result = transactionService.create(userId, req);

        assertThat(result.id()).isEqualTo(existingId);
        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
        verify(reconciliationService, never()).reconcileForUser(any());
    }

    // --- Idempotency key reused with a different request (gap review of SEC-06: the replay check
    // used to return the original transaction unconditionally once the key matched, with no check
    // that the rest of the request -- amount, account, category -- actually matched what was
    // recorded under that key the first time) ---

    private Transaction seededIdempotentTransaction(UUID accountId) {
        Transaction existing = new Transaction();
        ReflectionTestUtils.setField(existing, "id", UUID.randomUUID());
        existing.setUserId(userId);
        existing.setAccountId(accountId);
        existing.setDescription("Swiggy order");
        existing.setAmount(BigDecimal.valueOf(486));
        existing.setTxnType(Transaction.Type.EXPENSE);
        existing.setTxnDate(LocalDate.now());
        existing.setCategoryId(dummyCategory.getId());
        existing.setIdempotencyKey("client-key-1");
        return existing;
    }

    @Test
    void create_withAReusedIdempotencyKey_butADifferentAmount_rejectsRatherThanReturningTheStaleOriginal() {
        UUID accountId = UUID.randomUUID();
        Transaction existing = seededIdempotentTransaction(accountId);
        when(transactionRepository.findByUserIdAndIdempotencyKey(userId, "client-key-1"))
                .thenReturn(Optional.of(existing));
        when(categoryRepository.findById(dummyCategory.getId())).thenReturn(Optional.of(dummyCategory));

        // Same key, same everything except the amount -- a client bug, not a legitimate retry.
        var req = new TransactionDto.CreateRequest(accountId, null, existing.getTxnDate(),
                "Swiggy order", BigDecimal.valueOf(999), "EXPENSE", List.of(), "client-key-1");

        assertThatThrownBy(() -> transactionService.create(userId, req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already used for a different request");

        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void create_withAReusedIdempotencyKey_butADifferentAccount_rejectsRatherThanReturningTheStaleOriginal() {
        UUID accountId = UUID.randomUUID();
        UUID otherAccountId = UUID.randomUUID();
        Transaction existing = seededIdempotentTransaction(accountId);
        when(transactionRepository.findByUserIdAndIdempotencyKey(userId, "client-key-1"))
                .thenReturn(Optional.of(existing));
        when(categoryRepository.findById(dummyCategory.getId())).thenReturn(Optional.of(dummyCategory));

        var req = new TransactionDto.CreateRequest(otherAccountId, null, existing.getTxnDate(),
                "Swiggy order", BigDecimal.valueOf(486), "EXPENSE", List.of(), "client-key-1");

        assertThatThrownBy(() -> transactionService.create(userId, req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already used for a different request");

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void create_withAReusedIdempotencyKey_butADifferentExplicitCategory_rejectsRatherThanReturningTheStaleOriginal() {
        UUID accountId = UUID.randomUUID();
        Transaction existing = seededIdempotentTransaction(accountId);
        when(transactionRepository.findByUserIdAndIdempotencyKey(userId, "client-key-1"))
                .thenReturn(Optional.of(existing));
        when(categoryRepository.findById(dummyCategory.getId())).thenReturn(Optional.of(dummyCategory));

        var req = new TransactionDto.CreateRequest(accountId, "Travel", existing.getTxnDate(),
                "Swiggy order", BigDecimal.valueOf(486), "EXPENSE", List.of(), "client-key-1");

        assertThatThrownBy(() -> transactionService.create(userId, req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already used for a different request");

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void create_withABlankIdempotencyKey_isTreatedAsNoKeyAtAll() {
        when(categorizationService.suggest(eq(userId), anyString(), any(), any()))
                .thenReturn(new CategorizationService.Suggestion("Dining", "rule", UUID.randomUUID(), Transaction.DecisionSource.KEYWORD_MATCH, null));
        when(categorizationService.resolveOrCreateCategory(eq(userId), eq("Dining"))).thenReturn(dummyCategory);

        var req = new TransactionDto.CreateRequest(UUID.randomUUID(), null, LocalDate.now(),
                "Swiggy order", BigDecimal.valueOf(486), "EXPENSE", List.of(), "   ");

        transactionService.create(userId, req);

        verify(transactionRepository, never()).findByUserIdAndIdempotencyKey(any(), any());
        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getIdempotencyKey()).isNull();
    }

    // --- Account ownership on create() (previously: req.accountId() went straight onto the
    // transaction with no check it belonged to the caller -- any authenticated user could POST
    // with another user's accountId and both plant a transaction against it AND silently move
    // that victim's real account balance via adjustAccountBalance()) ---

    @Test
    void create_rejectsAnAccountIdThatBelongsToAnotherUser() {
        UUID otherUsersAccountId = UUID.randomUUID();
        Account othersAccount = account(otherUsersAccountId, Account.Type.SAVINGS, BigDecimal.valueOf(1000));
        othersAccount.setUserId(otherUserId);
        when(accountRepository.findById(otherUsersAccountId)).thenReturn(Optional.of(othersAccount));

        var req = new TransactionDto.CreateRequest(otherUsersAccountId, "Dining", LocalDate.now(),
                "Swiggy order", BigDecimal.valueOf(486), "EXPENSE", List.of());

        assertThatThrownBy(() -> transactionService.create(userId, req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("does not belong to you");

        verify(transactionRepository, never()).save(any());
        verify(accountRepository, never()).save(any());
    }

    @Test
    void create_rejectsAnAccountIdThatDoesNotExistAtAll() {
        UUID missingAccountId = UUID.randomUUID();
        when(accountRepository.findById(missingAccountId)).thenReturn(Optional.empty());

        var req = new TransactionDto.CreateRequest(missingAccountId, "Dining", LocalDate.now(),
                "Swiggy order", BigDecimal.valueOf(486), "EXPENSE", List.of());

        assertThatThrownBy(() -> transactionService.create(userId, req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Account not found");

        verify(transactionRepository, never()).save(any());
    }

    @Test
    void update_rejectsZeroOrNegativeAmount() {
        UUID txnId = UUID.randomUUID();
        Transaction existing = ownedTransaction(txnId, userId);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(existing));

        var req = new TransactionDto.UpdateRequest(null, null, null, BigDecimal.valueOf(-100), null,
                null, null, null);

        assertThatThrownBy(() -> transactionService.update(userId, txnId, req))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("greater than zero");
        // The transaction's original amount must be untouched -- the field is only ever mutated
        // after validation passes.
        assertThat(existing.getAmount()).isEqualByComparingTo("100");
    }

    @Test
    void update_withoutAmountField_doesNotValidateOrTouchAmount() {
        UUID txnId = UUID.randomUUID();
        Transaction existing = ownedTransaction(txnId, userId);
        when(transactionRepository.findById(txnId)).thenReturn(Optional.of(existing));

        var req = new TransactionDto.UpdateRequest(null, "Just a description change", null, null, null,
                null, null, null);
        transactionService.update(userId, txnId, req);

        assertThat(existing.getAmount()).isEqualByComparingTo("100");
        assertThat(existing.getDescription()).isEqualTo("Just a description change");
    }

    /**
     * Locks in the bank-aware search wiring (PRD's "Improve Search"): a keyword that matches a
     * bank's official/short name via com.finora.util.BankRegistry.search(...) should resolve to
     * that bank's id(s) and get passed down to the repository's `bankIds` parameter, so the
     * query's `a.bankId IN :bankIds` branch can match accounts held with that bank even when the
     * transaction's own description/merchant text says nothing about it. See
     * TransactionRepositoryIT.search_matchesByBankOfficialName_evenWhenDescriptionDoesNotMentionIt
     * for the corresponding real-Postgres proof that the query itself works correctly.
     */
    @Test
    void search_withAKeywordMatchingABankName_resolvesAndPassesThatBanksIdToTheRepository() {
        Page<Transaction> emptyPage = new PageImpl<>(List.of());
        when(transactionRepository.search(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(emptyPage);

        var filter = new TransactionDto.FilterRequest(null, null, null, null, null, null, null,
                "Punjab National Bank", 0, 20, null, null);
        transactionService.search(userId, filter);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> bankIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).search(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                bankIdsCaptor.capture(), any(Pageable.class));

        assertThat(bankIdsCaptor.getValue()).containsExactly("PNB");
    }

    @Test
    void search_withAKeywordMatchingNoBankName_passesTheNoMatchSentinelRatherThanAnEmptyList() {
        // An empty list bound to a JPQL `IN` parameter is a real footgun across JPA providers --
        // TransactionService always passes a non-empty placeholder instead (see its own
        // NO_BANK_MATCH_SENTINEL comment) so the repository never has to reason about that case.
        Page<Transaction> emptyPage = new PageImpl<>(List.of());
        when(transactionRepository.search(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(emptyPage);

        var filter = new TransactionDto.FilterRequest(null, null, null, null, null, null, null,
                "swiggy", 0, 20, null, null);
        transactionService.search(userId, filter);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> bankIdsCaptor = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).search(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                bankIdsCaptor.capture(), any(Pageable.class));

        assertThat(bankIdsCaptor.getValue()).isNotEmpty();
        assertThat(bankIdsCaptor.getValue()).doesNotContain("PNB", "SBI", "HDFC");
    }

    /**
     * Bug fix: search() used to call page.getContent() and hand back a bare List, discarding the
     * totalElements/totalPages Spring Data's Page<Transaction> already computed as part of the
     * same query -- leaving the frontend with no way to know whether a next page of results
     * existed at all (see PagedResponse's own doc comment). Locks in that the real total now
     * survives all the way out, not just the current page's own (smaller) content size.
     */
    @Test
    void search_returnsTheRepositorysRealTotalElementsAndTotalPages_notJustThisPagesContentSize() {
        List<Transaction> pageContent = List.of(ownedTransaction(UUID.randomUUID(), userId), ownedTransaction(UUID.randomUUID(), userId));
        // 2 transactions on this page, but 45 total across the full result set at size 10 --
        // exactly the distinction a bare `.size()` on the returned list could never make.
        Page<Transaction> page = new PageImpl<>(pageContent, org.springframework.data.domain.PageRequest.of(0, 10), 45);
        when(transactionRepository.search(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(page);

        var filter = new TransactionDto.FilterRequest(null, null, null, null, null, null, null, null, 0, 10, null, null);
        var result = transactionService.search(userId, filter);

        assertThat(result.content()).hasSize(2);
        assertThat(result.totalElements()).isEqualTo(45);
        assertThat(result.totalPages()).isEqualTo(5);
        assertThat(result.page()).isEqualTo(0);
        assertThat(result.size()).isEqualTo(10);
    }

    /**
     * BH-009. {@code sortDir} went straight into {@code Sort.Direction.fromString} unvalidated,
     * so a bogus value threw {@code IllegalArgumentException} and 500'd -- in the same method
     * whose own comment explains that {@code page} and {@code size} are clamped precisely so a
     * malformed param stops doing that. Two of three unvalidated inputs were fixed and the third
     * was missed; this is the one that closes it. Not merely "does not throw" -- captures the
     * {@code Pageable} the repository actually received and asserts the fallback direction is
     * DESC, the documented behaviour, not just the absence of a crash.
     */
    @Test
    void search_withAnUnrecognisedSortDir_fallsBackToDescendingRatherThanThrowing() {
        when(transactionRepository.search(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        var filter = new TransactionDto.FilterRequest(null, null, null, null, null, null, null, null, 0, 20, null, "bogus");
        transactionService.search(userId, filter);

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(transactionRepository).search(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), pageableCaptor.capture());
        Sort.Order order = pageableCaptor.getValue().getSort().getOrderFor("txnDate");
        assertThat(order)
                .as("an unrecognised sortDir must still produce a real sort, not fail the search")
                .isNotNull();
        assertThat(order.getDirection())
                .as("and the fallback must be the documented default, not an arbitrary one")
                .isEqualTo(Sort.Direction.DESC);
    }
}
