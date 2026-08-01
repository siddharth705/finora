package com.finora.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "transactions")
// Soft delete: repository.delete(...) issues this UPDATE instead of a real DELETE, and
// every SELECT against this entity (including derived-query and JPQL @Query methods)
// automatically excludes soft-deleted rows — no repository code had to change.
//
// The version = version + 1 / AND version = ? clauses aren't optional: BaseEntity has @Version,
// and Hibernate binds a second (version) parameter on delete for any versioned entity regardless
// of custom @SQLDelete SQL. Omitting it throws "No value specified for parameter 2" on every
// direct delete() call — confirmed against Hibernate 6.5.2 (this project's version) by real
// bug reports of this exact mismatch.
@SQLDelete(sql = "UPDATE transactions SET deleted_at = now(), version = version + 1 WHERE id = ? AND version = ?")
@SQLRestriction("deleted_at IS NULL")
public class Transaction extends BaseEntity {

    public enum Type { INCOME, EXPENSE }
    public enum ReconciliationStatus { OK, DUPLICATE, TRANSFER, REFUND }
    public enum Source { MANUAL, CSV_IMPORT }

    // Which mechanism produced this transaction's category -- explainability, not a decision
    // input (see docs/rule-engine-relationship-engine-eds.md §3.2). KEYWORD_MATCH is the static
    // CategoryRules table (util package); GLOBAL_RULE/USER_RULE are the new category_rules DB
    // table (RuleEngineService); MERCHANT_DEFAULT is "nothing matched, fell through to Other".
    public enum DecisionSource { GLOBAL_RULE, USER_RULE, LEARNED_PATTERN, KEYWORD_MATCH, MERCHANT_DEFAULT, MANUAL, FILE_PROVIDED }

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "category_id")
    private UUID categoryId;

    @Column(name = "txn_date", nullable = false)
    private LocalDate txnDate;

    private String description;
    private String merchant;

    @Column(name = "merchant_id")
    private UUID merchantId;

    @Column(name = "payment_method")
    private String paymentMethod;

    @Column(nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "txn_type", nullable = false)
    private Type txnType;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "tags")
    private List<String> tags;

    private String notes;

    @Column(name = "is_duplicate_of")
    private UUID isDuplicateOf;

    @Column(name = "is_transfer", nullable = false)
    private boolean isTransfer = false;

    @Column(name = "transfer_pair_id")
    private UUID transferPairId;

    @Column(name = "is_recurring", nullable = false)
    private boolean isRecurring = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "reconciliation_status", nullable = false)
    private ReconciliationStatus reconciliationStatus = ReconciliationStatus.OK;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Source source = Source.MANUAL;

    @Column(name = "needs_category_review", nullable = false)
    private boolean needsCategoryReview = false;

    // Distinct from needsCategoryReview: this tracks WHO last set the category, not whether it's
    // still awaiting a decision. False for anything the suggestion engine picked (rule match,
    // learned merchant match, or the low-confidence "Other" default) and for every CSV-imported
    // row; flipped true the moment a user explicitly sets or corrects a category — via the Ask
    // Once review queue, the Ledger's category dropdown, bulk recategorize, or the transaction
    // edit form — so the UI can show "Automatically Assigned" vs "Manually Modified" and the
    // categorization engine is never mistaken for having the final word.
    @Column(name = "category_manually_set", nullable = false)
    private boolean categoryManuallySet = false;

    // Null for manual transactions and anything imported before V10. This is what lets
    // "Delete Statement Import" remove exactly this batch's transactions instead of an
    // all-or-nothing wipe — see StatementImportService.delete().
    @Column(name = "statement_import_id")
    private UUID statementImportId;

    // See DecisionSource above. Defaults to MERCHANT_DEFAULT (matches the V17 column default) so
    // any write path that doesn't explicitly set this fails safe to the least-specific label
    // rather than silently claiming a rule/learning match that didn't happen.
    @Enumerated(EnumType.STRING)
    @Column(name = "decision_source", nullable = false)
    private DecisionSource decisionSource = DecisionSource.MERCHANT_DEFAULT;

    // Only set when decisionSource is GLOBAL_RULE or USER_RULE -- see CategoryRule.
    @Column(name = "decision_rule_id")
    private UUID decisionRuleId;

    // Only set when reconciliationStatus is REFUND -- points back at the original EXPENSE
    // transaction this INCOME transaction reverses. See ReconciliationService's refund pass.
    @Column(name = "refund_of_transaction_id")
    private UUID refundOfTransactionId;

    // Phase 1 "capture facts" (docs/engineering/financial-document-intelligence-principles.md):
    // best-effort, nullable, never guessed -- see StagedRow.referenceNumber/balanceAfter for the
    // exact staging-time extraction these two are copied from at confirm() time. referenceNumber
    // covers reference number, cheque number, and instrument ID alike -- every real statement
    // seen so far uses exactly one of those labels for what is structurally the same column, not
    // two distinct ones on the same file.
    @Column(name = "reference_number")
    private String referenceNumber;

    @Column(name = "balance_after")
    private BigDecimal balanceAfter;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getAccountId() { return accountId; }
    public void setAccountId(UUID accountId) { this.accountId = accountId; }
    public UUID getCategoryId() { return categoryId; }
    public void setCategoryId(UUID categoryId) { this.categoryId = categoryId; }
    public LocalDate getTxnDate() { return txnDate; }
    public void setTxnDate(LocalDate txnDate) { this.txnDate = txnDate; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getMerchant() { return merchant; }
    public void setMerchant(String merchant) { this.merchant = merchant; }
    public UUID getMerchantId() { return merchantId; }
    public void setMerchantId(UUID merchantId) { this.merchantId = merchantId; }
    public String getPaymentMethod() { return paymentMethod; }
    public void setPaymentMethod(String paymentMethod) { this.paymentMethod = paymentMethod; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Type getTxnType() { return txnType; }
    public void setTxnType(Type txnType) { this.txnType = txnType; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public UUID getIsDuplicateOf() { return isDuplicateOf; }
    public void setIsDuplicateOf(UUID isDuplicateOf) { this.isDuplicateOf = isDuplicateOf; }
    public boolean isTransfer() { return isTransfer; }
    public void setTransfer(boolean transfer) { isTransfer = transfer; }
    public UUID getTransferPairId() { return transferPairId; }
    public void setTransferPairId(UUID transferPairId) { this.transferPairId = transferPairId; }
    public boolean isRecurring() { return isRecurring; }
    public void setRecurring(boolean recurring) { isRecurring = recurring; }
    public ReconciliationStatus getReconciliationStatus() { return reconciliationStatus; }
    public void setReconciliationStatus(ReconciliationStatus s) { this.reconciliationStatus = s; }
    public Source getSource() { return source; }
    public void setSource(Source source) { this.source = source; }
    public boolean isNeedsCategoryReview() { return needsCategoryReview; }
    public void setNeedsCategoryReview(boolean needsCategoryReview) { this.needsCategoryReview = needsCategoryReview; }
    public boolean isCategoryManuallySet() { return categoryManuallySet; }
    public void setCategoryManuallySet(boolean categoryManuallySet) { this.categoryManuallySet = categoryManuallySet; }
    public UUID getStatementImportId() { return statementImportId; }
    public void setStatementImportId(UUID statementImportId) { this.statementImportId = statementImportId; }
    public DecisionSource getDecisionSource() { return decisionSource; }
    public void setDecisionSource(DecisionSource decisionSource) { this.decisionSource = decisionSource; }
    public UUID getDecisionRuleId() { return decisionRuleId; }
    public void setDecisionRuleId(UUID decisionRuleId) { this.decisionRuleId = decisionRuleId; }
    public UUID getRefundOfTransactionId() { return refundOfTransactionId; }
    public void setRefundOfTransactionId(UUID refundOfTransactionId) { this.refundOfTransactionId = refundOfTransactionId; }
    public String getReferenceNumber() { return referenceNumber; }
    public void setReferenceNumber(String referenceNumber) { this.referenceNumber = referenceNumber; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public void setBalanceAfter(BigDecimal balanceAfter) { this.balanceAfter = balanceAfter; }
}
