package com.finora.service;

import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.entity.Transaction;
import com.finora.entity.TransactionRelationship;
import com.finora.integrations.google.merchant.GmailReconciliationMatcher;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Shared harness for the reconciliation accuracy benchmark (docs/proposals/reconciliation-
 * benchmark/). Deliberately named so it does NOT match backend/pom.xml's surefire {@code includes}
 * (**Test*.java, *Test.java, *Tests.java, *TestCase.java) -- same convention {@link
 * ReconciliationScalingBenchmark} already established. Every scenario here encodes what a CORRECT
 * reconciliation verdict is, independent of what {@link ReconciliationService} currently does; a
 * red assertion is the benchmark finding a real gap, not a broken build. A benchmark suite where
 * roughly half the assertions are expected to fail must never run inside the default {@code mvn
 * test} gate that blocks every PR -- see docs/proposals/reconciliation-benchmark/README.md for how
 * to run this suite on purpose.
 *
 * <p>Mocking shape is copied from {@link ReconciliationServiceTest} exactly -- same repositories
 * mocked the same way -- so this benchmark exercises the real {@link ReconciliationService} code
 * path (real pass logic, real thresholds from {@link ReconciliationPolicy}), not a reimplementation
 * of it. The only thing faked is persistence.
 */
abstract class ReconciliationBenchmarkSupport {

    protected TransactionRepository transactionRepository;
    protected AccountRepository accountRepository;
    protected RelationshipService relationshipService;
    protected AuditService auditService;
    protected TransactionGraphService transactionGraphService;
    protected GmailReconciliationMatcher gmailReconciliationMatcher;
    protected StatementImportRepository statementImportRepository;
    protected ReconciliationService reconciliationService;

    protected final UUID userId = UUID.randomUUID();
    private List<Account> liveAccounts;
    private List<StatementImport> ccStatements;
    private final AtomicInteger accountCounter = new AtomicInteger();

    @BeforeEach
    void baseSetUp() {
        transactionRepository = mock(TransactionRepository.class);
        accountRepository = mock(AccountRepository.class);
        relationshipService = mock(RelationshipService.class);
        auditService = mock(AuditService.class);
        transactionGraphService = mock(TransactionGraphService.class);
        gmailReconciliationMatcher = mock(GmailReconciliationMatcher.class);
        statementImportRepository = mock(StatementImportRepository.class);
        liveAccounts = new ArrayList<>();
        ccStatements = new ArrayList<>();
        when(accountRepository.findByUserId(userId)).thenAnswer(inv -> new ArrayList<>(liveAccounts));
        reconciliationService = new ReconciliationService(transactionRepository, accountRepository, relationshipService,
                auditService, transactionGraphService, gmailReconciliationMatcher, statementImportRepository);
    }

    // --- Fixture builders -----------------------------------------------------------------

    /** A live, non-card account (savings/current/wallet). */
    protected Account account() {
        Account a = new Account();
        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
        a.setUserId(userId);
        liveAccounts.add(a);
        return a;
    }

    /** A live card account whose masked number ends in {@code last4} -- see last4Of/last4CandidatesIn
     *  in ReconciliationService for why only the trailing 4 digits ever matter for matching. */
    protected Account cardAccount(String last4) {
        Account a = account();
        a.setAccountNumberMasked("XXXXXXXXXXXX" + last4);
        return a;
    }

    /** createdAt derived from the txn date at a distinct second per call, so creation-order
     *  tiebreaks (duplicate canonical selection) are deterministic without every scenario having
     *  to invent its own Instant. */
    protected Transaction txn(Account account, LocalDate date, String amount, Transaction.Type type, String description) {
        int seq = accountCounter.incrementAndGet();
        return txn(account, date, amount, type, description, date.atStartOfDay(java.time.ZoneOffset.UTC).plusSeconds(seq).toInstant());
    }

    protected Transaction txn(Account account, LocalDate date, String amount, Transaction.Type type,
                              String description, Instant createdAt) {
        Transaction t = new Transaction();
        ReflectionTestUtils.setField(t, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(t, "createdAt", createdAt);
        t.setUserId(userId);
        t.setAccountId(account.getId());
        t.setTxnDate(date);
        t.setAmount(new BigDecimal(amount));
        t.setTxnType(type);
        t.setDescription(description);
        t.setReconciliationStatus(Transaction.ReconciliationStatus.OK);
        return t;
    }

    /** Wires the fetches reconcileForUser() makes: the live-account-scoped transaction list, and
     *  (harmlessly, for scenarios that don't need it) the unscoped findByUserId used only by the
     *  dead-account edge cleanup -- returning the same set there is always safe since every account
     *  built via account()/cardAccount() above is already registered live. */
    protected void loadTransactions(Transaction... all) {
        List<Transaction> list = List.of(all);
        when(transactionRepository.findByUserIdAndAccountIdIn(eq(userId), any())).thenReturn(list);
        when(transactionRepository.findByUserId(userId)).thenReturn(list);
    }

    /** All of this user's OWN_ACCOUNT relationship identifiers -- see RelationshipService
     *  .ownAccountIdentifierValues, already normalized (lowercase) by that service in production;
     *  scenarios here pass already-normalized values for the same reason. */
    protected void ownAccountIdentifiers(String... normalizedValues) {
        when(relationshipService.ownAccountIdentifierValues(userId)).thenReturn(List.of(normalizedValues));
    }

    /** Stubs GmailReconciliationMatcher.findMatchAmongTransactions for one Gmail-sourced
     *  transaction, regardless of which bank candidates ReconciliationService's own amount/date
     *  pre-filter assembles for it -- the matcher's own fuzzy-merchant logic is out of scope for
     *  this pass-level benchmark, which is testing ReconciliationService's wiring around it, not
     *  GmailReconciliationMatcherTest's own subject. */
    protected void gmailMatches(Transaction gmailTxn, Transaction bankMatch) {
        when(gmailReconciliationMatcher.findMatchAmongTransactions(eq(gmailTxn), any()))
                .thenReturn(Optional.of(bankMatch));
    }

    /** A credit-card statement plus the specific charge rows it billed (what
     *  TransactionRepository.findByStatementImportId would return for it) -- mirrors
     *  ReconciliationServiceTest's own ccStatement() helper. Registers the card account as live
     *  automatically since every StatementImport here is built via cardAccount() above. */
    protected StatementImport ccStatement(Account cardAccount, String totalAmountDue, LocalDate paymentDueDate,
                                           Transaction... charges) {
        StatementImport s = new StatementImport();
        ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
        s.setUserId(userId);
        s.setAccountId(cardAccount.getId());
        s.setTotalAmountDue(new BigDecimal(totalAmountDue));
        s.setPaymentDueDate(paymentDueDate);
        ccStatements.add(s);
        when(statementImportRepository.findByUserIdAndTotalAmountDueIsNotNull(userId)).thenReturn(List.copyOf(ccStatements));
        when(transactionRepository.findByStatementImportId(s.getId())).thenReturn(List.of(charges));
        return s;
    }

    protected void run() {
        reconciliationService.reconcileForUser(userId);
    }

    /** Every graph edge written this run, across however many (0 or 1) times linkAll was actually
     *  invoked -- see TransactionGraphService.linkAll's own doc comment: it is called once per
     *  reconcile() run, and only if at least one pass produced a pending edge, so a scenario with
     *  no matches at all must not fail this capture just because linkAll was never called. */
    @SuppressWarnings("unchecked")
    protected List<TransactionGraphService.PendingEdge> capturedEdges() {
        ArgumentCaptor<List<TransactionGraphService.PendingEdge>> captor = ArgumentCaptor.forClass(List.class);
        verify(transactionGraphService, atLeast(0)).linkAll(captor.capture());
        List<TransactionGraphService.PendingEdge> all = new ArrayList<>();
        for (List<TransactionGraphService.PendingEdge> batch : captor.getAllValues()) all.addAll(batch);
        return all;
    }

    /** The single edge between two specific transactions, in either direction, of the given type --
     *  fails loudly (not with an empty Optional) when none exists, since every caller here already
     *  knows a match should exist and just wants the edge's confidence/status to inspect. */
    protected TransactionGraphService.PendingEdge edgeBetween(Transaction a, Transaction b,
                                                               TransactionRelationship.RelationshipType type) {
        return capturedEdges().stream()
                .filter(e -> e.relationshipType() == type)
                .filter(e -> (e.fromTransactionId().equals(a.getId()) && e.toTransactionId().equals(b.getId()))
                        || (e.fromTransactionId().equals(b.getId()) && e.toTransactionId().equals(a.getId())))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + type + " edge found between the two transactions. "
                        + "Edges actually written: " + capturedEdges()));
    }
}
