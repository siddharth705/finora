package com.finora.imports.product;

import com.finora.entity.Account;

/**
 * A financial product a statement can describe, and where it belongs in Finora.
 *
 * The engine's question changed with this type. It used to ask "which account is this?", which has
 * no honest answer for a term-deposit summary or an installment schedule -- so those were either
 * forced into being accounts (offering the user empty accounts to confirm) or dropped. The question
 * is now "which financial PRODUCT is this?", which every section of a combined statement has a real
 * answer to, including "I don't know yet".
 *
 * Each constant carries its own routing, so the mapping from product to domain lives in one place
 * rather than being re-derived by every caller. Nothing here is bank-specific: these are the
 * products Indian retail banking offers, not the sections one bank's PDF happens to contain.
 */
public enum FinancialProductType {

    // --- Transaction accounts -> the Accounts module -------------------------------------------
    SAVINGS(Domain.ACCOUNT, Account.Type.SAVINGS, null),
    // Finora has no distinct CURRENT account type today. Current accounts behave identically for
    // everything the importer does (transactions against a running balance), so they are stored as
    // SAVINGS rather than blocked -- but they keep their own product type, so the day a current
    // account needs different treatment the classification is already there to key off.
    CURRENT(Domain.ACCOUNT, Account.Type.SAVINGS, null),
    OVERDRAFT(Domain.ACCOUNT, Account.Type.SAVINGS, null),
    WALLET(Domain.ACCOUNT, Account.Type.WALLET, null),

    // --- Credit -------------------------------------------------------------------------------
    CREDIT_CARD(Domain.CREDIT_CARD, Account.Type.CREDIT_CARD, null),

    // --- Investments -> the Investments module, which is Account.Type.INVESTMENT plus a kind ----
    // Deliberately NOT a separate Deposits module: deposits sit alongside mutual funds, stocks and
    // PPF in one Investments view, because a customer thinks of them as one thing -- money they
    // have put away -- and splitting them costs navigation clarity for no benefit.
    FIXED_DEPOSIT(Domain.INVESTMENT, Account.Type.INVESTMENT, "FD"),
    RECURRING_DEPOSIT(Domain.INVESTMENT, Account.Type.INVESTMENT, "RD"),
    PPF(Domain.INVESTMENT, Account.Type.INVESTMENT, "PPF"),
    EPF(Domain.INVESTMENT, Account.Type.INVESTMENT, "EPF"),
    NPS(Domain.INVESTMENT, Account.Type.INVESTMENT, "NPS"),
    MUTUAL_FUND(Domain.INVESTMENT, Account.Type.INVESTMENT, "Mutual Fund"),
    DEMAT(Domain.INVESTMENT, Account.Type.INVESTMENT, "Demat"),

    // --- Products Finora does not model yet ----------------------------------------------------
    // Recognisable but with nowhere to go. They are classified rather than ignored so the review
    // screen can say what was found and why nothing was created, instead of silently dropping a
    // product the customer holds -- and so the Capability Backlog can count how often each appears.
    LOAN(Domain.NOT_MODELLED_YET, null, null),
    INSURANCE(Domain.NOT_MODELLED_YET, null, null),
    FOREX_CARD(Domain.NOT_MODELLED_YET, null, null),

    /** Something financial was found and could not be identified. Never guessed into a real
     *  product: it goes to the user for a one-time answer (see FinancialProductClassifier). */
    UNKNOWN(Domain.NEEDS_USER_INPUT, null, null);

    /** Where a product lands in Finora once classified. */
    public enum Domain { ACCOUNT, CREDIT_CARD, INVESTMENT, NOT_MODELLED_YET, NEEDS_USER_INPUT }

    private final Domain domain;
    private final Account.Type accountType;
    private final String investmentKind;

    FinancialProductType(Domain domain, Account.Type accountType, String investmentKind) {
        this.domain = domain;
        this.accountType = accountType;
        this.investmentKind = investmentKind;
    }

    public Domain domain() { return domain; }

    /** The Account row to create, or null when this product has no home yet or needs the user. */
    public Account.Type accountType() { return accountType; }

    /** The Investments-module discriminator, non-null only for {@link Domain#INVESTMENT}. */
    public String investmentKind() { return investmentKind; }

    /** True when this product carries a transaction table worth importing rows from. A deposit is a
     *  balance and a schedule, not a ledger -- its installments appear as transactions on the
     *  savings account that funds it, and importing them twice would double-count. */
    public boolean hasTransactions() {
        return domain == Domain.ACCOUNT || domain == Domain.CREDIT_CARD;
    }

    /** True when nothing may be created without asking the user first. */
    public boolean requiresUserConfirmation() {
        return domain == Domain.NEEDS_USER_INPUT || domain == Domain.NOT_MODELLED_YET;
    }
}
