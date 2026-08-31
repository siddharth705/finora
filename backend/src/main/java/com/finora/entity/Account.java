package com.finora.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "accounts")
// version = version + 1 / AND version = ? isn't optional here: Account extends BaseEntity,
// which has @Version. Hibernate binds a second parameter (the current version) whenever a
// versioned entity is deleted, custom @SQLDelete SQL or not — a SQL string with only one `?`
// throws "No value specified for parameter 2" on every direct delete() call, confirmed against
// Hibernate 6.5.2 (the version this project runs) by real-world bug reports of this exact
// mismatch. See https://vladmihalcea.com/soft-delete-jpa-version/ for the canonical pattern.
@SQLDelete(sql = "UPDATE accounts SET deleted_at = now(), version = version + 1 WHERE id = ? AND version = ?")
@SQLRestriction("deleted_at IS NULL")
public class Account extends BaseEntity {

    public enum Type { SAVINGS, CREDIT_CARD, WALLET, INVESTMENT }

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_type", nullable = false)
    private Type accountType;

    @Column(nullable = false)
    private BigDecimal balance = BigDecimal.ZERO;

    @Column(name = "credit_limit")
    private BigDecimal creditLimit;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "investment_kind")
    private String investmentKind;

    // Financial Product Discovery (V49). productType is strictly finer-grained than accountType --
    // SAVINGS/CURRENT/OVERDRAFT all store as accountType SAVINGS, and every deposit/fund kind
    // stores as INVESTMENT -- so keeping the classification means the day a current account needs
    // different treatment, the information is already here rather than needing re-derivation from
    // a statement nobody kept.
    @Column(name = "product_type", length = 32)
    private String productType;

    // A one-way hash of institution + this product's own full number, so re-importing next month's
    // statement recognises the same fixed deposit instead of creating another one and
    // double-counting it. A hash rather than the number: equality is all this column is for, and
    // accountNumberMasked already covers display. See ProductIdentity.
    @Column(name = "product_identity_hash", length = 64)
    private String productIdentityHash;

    // What makes a deposit a DEPOSIT rather than a name and a balance (V51). All nullable and
    // populated only for the product types they apply to -- see
    // com.finora.imports.product.ProductAttributes for the full reasoning. A fixed deposit has no
    // installmentAmount; a recurring deposit has no principalAmount, since its value builds up
    // over the schedule rather than starting as a lump sum.
    @Column(name = "principal_amount", precision = 14, scale = 2)
    private BigDecimal principalAmount;

    @Column(name = "interest_rate", precision = 6, scale = 2)
    private BigDecimal interestRate;

    @Column(name = "maturity_date")
    private LocalDate maturityDate;

    @Column(name = "maturity_amount", precision = 14, scale = 2)
    private BigDecimal maturityAmount;

    @Column(name = "installment_amount", precision = 14, scale = 2)
    private BigDecimal installmentAmount;

    @Column(name = "installments_paid")
    private Integer installmentsPaid;

    @Column(name = "installments_total")
    private Integer installmentsTotal;

    // Neither is required — most accounts are still created with just a name/type/balance the
    // way they always were. Populated automatically when a statement's own header carries an
    // "Account Holder" / account-number-like column (see CsvImportService's detection), or set
    // by hand on the Accounts page. accountNumberMasked deliberately never holds a full,
    // unmasked number: real bank exports hand us an already-partially-masked value (e.g.
    // "XXXXXX4587"), so there's nothing more revealing than that to store in the first place.
    @Column(name = "account_holder_name")
    private String accountHolderName;

    @Column(name = "account_number_masked")
    private String accountNumberMasked;

    // Stable identifier into com.finora.util.BankRegistry -- resolves the official bank
    // name/logo/brand color independently of `name` above, which the user is free to rename to
    // a personal nickname (e.g. "Salary Account") without losing the bank identity used to
    // render the logo badge. Defaults to "OTHER" for accounts created before this field existed
    // or where no bank could be detected at import time -- never null in practice (see the V14
    // migration's NOT NULL DEFAULT), but defaulted here too for any in-memory `new Account()`.
    @Column(name = "bank_id", nullable = false)
    private String bankId = "OTHER";

    // Both optional -- entered manually on the "add account" form, or detected from a statement's
    // own branch/IFSC columns when present (see CsvImportService). ifscCode is deliberately a
    // free-form VARCHAR(11), not validated against BankRegistry's ifscPrefix at the DB level:
    // a mismatch there (e.g. a user fat-fingering it) shouldn't block saving the account.
    @Column(name = "branch_name")
    private String branchName;

    @Column(name = "ifsc_code")
    private String ifscCode;

    /** Which {@code StatementImport} most recently SET (not added to) this account's balance --
     *  null means either nothing ever has, or a manual balance edit ({@code AccountService
     *  .update}) invalidated the previous claim. See the "absolute balance reversal" design spec's
     *  "live anchor" section: this is what lets a later reversal tell "that statement's SET is
     *  still live under the current balance" apart from "something else has already overwritten
     *  it," without reconstructing history. Written by {@code ImportService.persistSection}'s
     *  ABSOLUTE branch, {@code AccountService.update} (cleared on a manual edit), and {@code
     *  StatementImportService.reverseAbsoluteContribution} (cleared after a successful reversal). */
    @Column(name = "last_absolute_set_statement_id")
    private UUID lastAbsoluteSetStatementId;

    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Type getAccountType() { return accountType; }
    public void setAccountType(Type accountType) { this.accountType = accountType; }
    public BigDecimal getBalance() { return balance; }
    public void setBalance(BigDecimal balance) { this.balance = balance; }
    public BigDecimal getCreditLimit() { return creditLimit; }
    public void setCreditLimit(BigDecimal creditLimit) { this.creditLimit = creditLimit; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public String getInvestmentKind() { return investmentKind; }
    public void setInvestmentKind(String investmentKind) { this.investmentKind = investmentKind; }
    public String getProductType() { return productType; }
    public void setProductType(String productType) { this.productType = productType; }
    public String getProductIdentityHash() { return productIdentityHash; }
    public void setProductIdentityHash(String productIdentityHash) { this.productIdentityHash = productIdentityHash; }
    public BigDecimal getPrincipalAmount() { return principalAmount; }
    public void setPrincipalAmount(BigDecimal principalAmount) { this.principalAmount = principalAmount; }
    public BigDecimal getInterestRate() { return interestRate; }
    public void setInterestRate(BigDecimal interestRate) { this.interestRate = interestRate; }
    public LocalDate getMaturityDate() { return maturityDate; }
    public void setMaturityDate(LocalDate maturityDate) { this.maturityDate = maturityDate; }
    public BigDecimal getMaturityAmount() { return maturityAmount; }
    public void setMaturityAmount(BigDecimal maturityAmount) { this.maturityAmount = maturityAmount; }
    public BigDecimal getInstallmentAmount() { return installmentAmount; }
    public void setInstallmentAmount(BigDecimal installmentAmount) { this.installmentAmount = installmentAmount; }
    public Integer getInstallmentsPaid() { return installmentsPaid; }
    public void setInstallmentsPaid(Integer installmentsPaid) { this.installmentsPaid = installmentsPaid; }
    public Integer getInstallmentsTotal() { return installmentsTotal; }
    public void setInstallmentsTotal(Integer installmentsTotal) { this.installmentsTotal = installmentsTotal; }
    public String getAccountHolderName() { return accountHolderName; }
    public void setAccountHolderName(String accountHolderName) { this.accountHolderName = accountHolderName; }
    public String getAccountNumberMasked() { return accountNumberMasked; }
    public void setAccountNumberMasked(String accountNumberMasked) { this.accountNumberMasked = accountNumberMasked; }
    public String getBankId() { return bankId; }
    public void setBankId(String bankId) { this.bankId = bankId; }
    public String getBranchName() { return branchName; }
    public void setBranchName(String branchName) { this.branchName = branchName; }
    public String getIfscCode() { return ifscCode; }
    public void setIfscCode(String ifscCode) { this.ifscCode = ifscCode; }
    public UUID getLastAbsoluteSetStatementId() { return lastAbsoluteSetStatementId; }
    public void setLastAbsoluteSetStatementId(UUID lastAbsoluteSetStatementId) { this.lastAbsoluteSetStatementId = lastAbsoluteSetStatementId; }
}
