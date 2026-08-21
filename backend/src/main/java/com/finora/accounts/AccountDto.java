package com.finora.accounts;

import com.finora.entity.Account;
import com.finora.util.BankRegistry;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record AccountDto(
        UUID id,
        String name,
        String accountType,
        BigDecimal balance,
        BigDecimal creditLimit,
        LocalDate dueDate,
        String investmentKind,
        String accountHolderName,
        String accountNumberMasked,
        // Optional -- entered manually or detected from a statement's own branch/IFSC columns.
        // Null (not "Not Available") when genuinely unknown, same convention as accountHolderName.
        String branchName,
        String ifscCode,
        BankDto bank,
        Instant lastImportedAt,
        LocalDate lastStatementPeriodStart,
        LocalDate lastStatementPeriodEnd,
        // How many statements / transactions this account has on file -- shown on the account
        // card so users can tell an actively-used account from one they set up once and forgot.
        // Computed in AccountService.listForUser from data already fetched for other fields
        // (statement imports) plus one grouped COUNT query (transactions) -- not per-account
        // queries, so this doesn't reintroduce the N+1 that lastImportedAt's fix avoided.
        int statementsCount,
        long transactionsCount,
        // Always "ACTIVE" today -- there's no archive/close-account feature yet, so every
        // account returned here (soft-deleted ones are already filtered out at the repository
        // level) is by definition still active. Kept as a real field rather than hardcoded in
        // the frontend so a future archive feature only has to start setting this to
        // "INACTIVE" here, without any UI changes.
        String status,

        // What makes a deposit a DEPOSIT rather than a name and a balance -- see
        // com.finora.imports.product.ProductAttributes. All nullable; populated only for the
        // product types they apply to.
        BigDecimal principalAmount,
        BigDecimal interestRate,
        LocalDate maturityDate,
        BigDecimal maturityAmount,
        BigDecimal installmentAmount,
        Integer installmentsPaid,
        Integer installmentsTotal
) {
    /** Everything BankLogo and the bank picker need to render/search a bank, resolved
     *  server-side from BankRegistry so the frontend never hardcodes bank metadata.
     *  logoPath points at a file that may not actually exist on disk yet (see BankRegistry's
     *  class comment) -- the frontend's BankLogo component falls back to the initials badge
     *  when it 404s/is missing, so this is safe to send unconditionally. */
    public record BankDto(String id, String officialName, String shortName, String colorHex, String initials,
                           String logoPath, String category, String websiteUrl, String ifscPrefix,
                           java.util.List<String> supportedAccountTypes) {
        public static BankDto from(BankRegistry.BankInfo info) {
            return new BankDto(info.id(), info.officialName(), info.shortName(), info.colorHex(), info.initials(),
                    info.logoPath(), info.category() != null ? info.category().name() : null, info.websiteUrl(),
                    info.ifscPrefix(), info.supportedAccountTypes());
        }

        /** Admin-added bank (com.finora.entity.Bank, see V26__custom_banks.sql) -- no bundled
         *  logo asset exists for these, so logoPath always points at the same generic fallback
         *  BankRegistry's own OTHER entry uses; BankLogo.tsx already falls back to an initials
         *  badge when a logo 404s, so this is safe to send unconditionally. supportedAccountTypes
         *  is hardcoded to the same retail-account default every BankRegistry entry uses today --
         *  not admin-configurable, to keep bank creation to the fields that actually vary. */
        public static BankDto fromCustom(com.finora.entity.Bank bank) {
            return new BankDto(bank.getId(), bank.getOfficialName(), bank.getShortName(), bank.getColorHex(),
                    bank.getInitials(), "/assets/banks/generic.svg", bank.getCategory(), bank.getWebsiteUrl(),
                    bank.getIfscPrefix(), java.util.List.of("SAVINGS", "CREDIT_CARD"));
        }

        public record CreateRequest(
                // Case-insensitive on purpose -- BankManagementService.createCustom uppercases
                // this before storing/comparing it, so an admin typing "iob" shouldn't be
                // rejected here just for not already having shifted it themselves.
                @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(
                        regexp = "^[A-Za-z0-9_]{2,30}$", message = "Use 2-30 letters, digits, or underscores")
                String id,
                @jakarta.validation.constraints.NotBlank String officialName,
                @jakarta.validation.constraints.NotBlank String shortName,
                String colorHex, String initials, String category,
                // Security fix: this was persisted with no scheme validation at all, so a
                // BANK_MANAGE admin could store "javascript:..." and the admin portal rendered it
                // as a real clickable <a href> for every other admin -- a stored XSS in the admin
                // origin. @Size matches banks.website_url VARCHAR(255) (V26), so an oversized
                // value is a clean 400 rather than a raw DB constraint violation.
                @com.finora.util.SafeHttpUrl @jakarta.validation.constraints.Size(max = 255) String websiteUrl,
                String ifscPrefix) {}

        /** Every field optional -- only supplied ones change (id is immutable once created, same
         *  reasoning as every other entity's primary key in this codebase). */
        public record UpdateRequest(String officialName, String shortName, String colorHex, String initials,
                                     String category,
                                     // Same stored-XSS fix as CreateRequest.websiteUrl above --
                                     // update() reaches the identical column and needed it just as
                                     // much as create() did.
                                     @com.finora.util.SafeHttpUrl @jakarta.validation.constraints.Size(max = 255)
                                     String websiteUrl,
                                     String ifscPrefix) {}
    }

    public static AccountDto from(Account a) {
        return from(a, BankDto.from(BankRegistry.get(a.getBankId())), null, null, null, 0, 0L);
    }

    /** Same as the 1-arg overload above, but with an already-resolved bank -- used wherever the
     *  caller needs to recognize an admin-added custom bank (BankManagementService.resolve),
     *  which the plain BankRegistry.get() call the 1-arg overload uses cannot see. */
    public static AccountDto from(Account a, BankDto bank) {
        return from(a, bank, null, null, null, 0, 0L);
    }

    /** The one real constructor path every overload above delegates to -- takes an
     *  already-resolved BankDto instead of resolving BankRegistry.get(a.getBankId()) internally,
     *  so a caller that's bank-registry-aware AND custom-bank-aware (BankManagementService) can
     *  supply the correct one regardless of which source the account's bank actually came from.
     *
     *  <p>lastImportedAt/lastStatementPeriodStart/lastStatementPeriodEnd describe this account's
     *  most recent StatementImport (by importedAt), or are all null if the account has never had
     *  a statement imported into it (manually created, or imported before this field was wired
     *  up) -- rather than a fabricated value. Taken as three primitives, not a StatementImport
     *  entity: every real caller (AccountService.listForUser, DataExportService) already resolves
     *  these from a {@code StatementImportRepository.StatementMetadata} projection row precisely
     *  to avoid loading a full entity (and its {@code fileContent}) just for this -- see that
     *  projection's own doc comment. Taking the entity here would have forced both of them to
     *  construct a throwaway, unsaved one just to satisfy this signature. */
    public static AccountDto from(Account a, BankDto bank, Instant lastImportedAt,
                                   LocalDate lastStatementPeriodStart, LocalDate lastStatementPeriodEnd,
                                   int statementsCount, long transactionsCount) {
        return new AccountDto(a.getId(), a.getName(), a.getAccountType().name(),
                a.getBalance(), a.getCreditLimit(), a.getDueDate(), a.getInvestmentKind(),
                a.getAccountHolderName(), a.getAccountNumberMasked(),
                a.getBranchName(), a.getIfscCode(),
                bank,
                lastImportedAt, lastStatementPeriodStart, lastStatementPeriodEnd,
                statementsCount, transactionsCount,
                "ACTIVE",
                a.getPrincipalAmount(), a.getInterestRate(), a.getMaturityDate(), a.getMaturityAmount(),
                a.getInstallmentAmount(), a.getInstallmentsPaid(), a.getInstallmentsTotal());
    }

    // Bug fix: this record had zero Bean Validation, and neither AccountController.create() nor
    // update() applied @Valid -- a null/blank name threw a raw NullPointerException/
    // DataIntegrityViolationException (unhandled 500) against accounts.name's NOT NULL VARCHAR(120)
    // constraint instead of a clean 400; every other free-text field had the same "let the DB
    // reject it" gap against its own column width. accountType is deliberately NOT annotated here
    // -- AccountService already hand-validates it with a clean 400 via parseAccountType(), and
    // that's a closed, known enum, not a free-text length concern.
    public record CreateRequest(@NotBlank @Size(max = 120) String name, String accountType, BigDecimal balance,
                                 BigDecimal creditLimit, LocalDate dueDate,
                                 @Size(max = 40) String investmentKind,
                                 @Size(max = 255) String accountHolderName,
                                 @Size(max = 64) String accountNumberMasked,
                                 @Size(max = 32) String bankId,
                                 @Size(max = 120) String branchName,
                                 @Size(max = 11) String ifscCode,
                                 // Only ImportService's confirm() ever populates these, from a
                                 // classified deposit's own DetectedAccountInfo/NewAccountRequest.
                                 // Every other caller (manual account creation from the Accounts
                                 // page) uses the 10-arg overload below and gets null for all seven
                                 // -- correct, since a hand-created account has none of these until
                                 // a statement is imported into it.
                                 BigDecimal principalAmount, BigDecimal interestRate, LocalDate maturityDate,
                                 BigDecimal maturityAmount, BigDecimal installmentAmount,
                                 Integer installmentsPaid, Integer installmentsTotal) {

        public CreateRequest(String name, String accountType, BigDecimal balance, BigDecimal creditLimit,
                             LocalDate dueDate, String investmentKind, String accountHolderName,
                             String accountNumberMasked, String bankId, String branchName, String ifscCode) {
            this(name, accountType, balance, creditLimit, dueDate, investmentKind, accountHolderName,
                    accountNumberMasked, bankId, branchName, ifscCode,
                    null, null, null, null, null, null, null);
        }
    }
}
