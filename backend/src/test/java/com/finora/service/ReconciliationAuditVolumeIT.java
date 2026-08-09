package com.finora.service;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.Account;
import com.finora.entity.AuditLog;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.AuditLogRepository;
import com.finora.repository.UserRepository;
import com.finora.transactions.TransactionDto;
import com.finora.transactions.TransactionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BH-044, the growth-rate half, measured rather than argued.
 *
 * <p>The finding: "Every reconciliation run writes a {@code RECONCILIATION_RUN} row — so every
 * transaction create, update and delete writes at least two audit rows. There is no retention
 * policy, no partitioning and no archival anywhere in the schema or the migrations."
 *
 * <p>This drives ordinary ledger editing through the real {@code TransactionService} against real
 * Postgres and counts what lands in {@code audit_logs}. Ordinary is the point: none of these
 * transactions duplicate, transfer or refund each other, which is what almost every real edit looks
 * like, and is exactly the case that produced an all-zero row.
 *
 * <p><b>The retention half is not fixed and is not tested here</b>, because it is an unresolved
 * product decision — see {@link AuditService}'s class comment for the seam, what has to be decided
 * before a sweep can be written, and why guessing a window would be the same mistake as guessing a
 * statement-retention period.
 */
class ReconciliationAuditVolumeIT extends AbstractIntegrationTest {

    private static final int EDITS = 12;

    @Autowired private TransactionService transactionService;
    @Autowired private UserRepository userRepository;
    @Autowired private AccountRepository accountRepository;
    @Autowired private AuditLogRepository auditLogRepository;

    private User user() {
        User user = new User();
        user.setEmail("recon-audit-volume-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Reconciliation Audit Volume IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    private Account account(User owner) {
        Account account = new Account();
        account.setUserId(owner.getId());
        account.setName("Everyday " + UUID.randomUUID());
        account.setAccountType(Account.Type.SAVINGS);
        account.setBalance(BigDecimal.ZERO);
        return accountRepository.save(account);
    }

    /**
     * Distinct amounts and descriptions on purpose. Identical rows would pair as duplicates and
     * every run WOULD have reclassified something — the benchmark would then measure the case the
     * change does not touch and pass while proving nothing.
     */
    private void createOrdinaryTransactions(User owner, Account account) {
        for (int i = 0; i < EDITS; i++) {
            transactionService.create(owner.getId(), new TransactionDto.CreateRequest(
                    account.getId(), null, LocalDate.of(2026, 7, 1).plusDays(i),
                    "ORDINARY PURCHASE " + i, new BigDecimal("100.").add(new BigDecimal(i)),
                    "EXPENSE", List.of()));
        }
    }

    private List<AuditLog> auditFor(User owner) {
        return auditLogRepository.findByUserIdOrderByCreatedAtDesc(owner.getId());
    }

    /**
     * The measurement, and the guard.
     *
     * <p>Twelve ordinary creates. Before this change each one wrote a {@code TRANSACTION_CREATED}
     * row AND an all-zero {@code RECONCILIATION_RUN} row beside it, at the same instant — twelve
     * rows of "reconciliation ran and reclassified nothing", against a table with no retention.
     * None survive now.
     *
     * <p>Asserted as zero rather than as a ratio because a ratio would need updating whenever an
     * unrelated audited action is added to the create path, and a test that needs constant updating
     * is one people update without reading (methodology.md §3).
     */
    @Test
    void ordinaryLedgerEditingWritesNoReconciliationRunRows() {
        User owner = user();
        Account account = account(owner);

        createOrdinaryTransactions(owner, account);

        List<AuditLog> audit = auditFor(owner);
        assertThat(audit)
                .as("the fixture has to have done something, or zero below is vacuous")
                .anyMatch(entry -> "TRANSACTION_CREATED".equals(entry.getAction()));
        assertThat(audit.stream().filter(entry -> "RECONCILIATION_RUN".equals(entry.getAction())))
                .as("%d ordinary creates used to leave %d all-zero reconciliation rows", EDITS, EDITS)
                .isEmpty();
    }

    /**
     * The control, and it is what stops the assertion above from being satisfied by a service that
     * simply stopped auditing.
     *
     * <p>Two identical transactions reconcile as a duplicate, so this run reclassifies something
     * and must still be recorded — with {@code recordedBecause=reclassified} naming why it survived.
     */
    @Test
    void aRunThatReclassifiesSomethingIsStillRecorded() {
        User owner = user();
        Account account = account(owner);

        for (int i = 0; i < 2; i++) {
            transactionService.create(owner.getId(), new TransactionDto.CreateRequest(
                    account.getId(), null, LocalDate.of(2026, 7, 10),
                    "SWIGGY*ORDR9182 BLR", new BigDecimal("486.00"), "EXPENSE", List.of()));
        }

        assertThat(auditFor(owner))
                .filteredOn(entry -> "RECONCILIATION_RUN".equals(entry.getAction()))
                .as("a run that flags a duplicate is the audit trail's whole subject")
                .isNotEmpty()
                .allSatisfy(entry -> assertThat(entry.getMetadata())
                        .containsEntry("recordedBecause", "reclassified"));
    }
}
