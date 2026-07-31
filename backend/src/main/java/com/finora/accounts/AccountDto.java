package com.finora.accounts;

import com.finora.entity.Account;
import com.finora.entity.StatementImport;
import com.finora.util.BankRegistry;

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
        String status
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
                String colorHex, String initials, String category, String websiteUrl, String ifscPrefix) {}

        /** Every field optional -- only supplied ones change (id is immutable once created, same
         *  reasoning as every other entity's primary key in this codebase). */
        public record UpdateRequest(String officialName, String shortName, String colorHex, String initials,
                                     String category, String websiteUrl, String ifscPrefix) {}
    }

    public static AccountDto from(Account a) {
        return from(a, BankDto.from(BankRegistry.get(a.getBankId())), null, 0, 0L);
    }

    /** Same as the 1-arg overload above, but with an already-resolved bank -- used wherever the
     *  caller needs to recognize an admin-added custom bank (BankManagementService.resolve),
     *  which the plain BankRegistry.get() call the 1-arg overload uses cannot see. */
    public static AccountDto from(Account a, BankDto bank) {
        return from(a, bank, null, 0, 0L);
    }

    /** latestImport is this account's most recent StatementImport (by importedAt), or null if
     *  the account has never had a statement imported into it (manually created, or imported
     *  accounts before this field was wired up) -- lastImportedAt/statement period are simply
     *  null in that case rather than a fabricated value. */
    public static AccountDto from(Account a, StatementImport latestImport, int statementsCount, long transactionsCount) {
        return from(a, BankDto.from(BankRegistry.get(a.getBankId())), latestImport, statementsCount, transactionsCount);
    }

    /** The one real constructor path every overload above delegates to -- takes an
     *  already-resolved BankDto instead of resolving BankRegistry.get(a.getBankId()) internally,
     *  so a caller that's bank-registry-aware AND custom-bank-aware (BankManagementService) can
     *  supply the correct one regardless of which source the account's bank actually came from. */
    public static AccountDto from(Account a, BankDto bank, StatementImport latestImport, int statementsCount, long transactionsCount) {
        return new AccountDto(a.getId(), a.getName(), a.getAccountType().name(),
                a.getBalance(), a.getCreditLimit(), a.getDueDate(), a.getInvestmentKind(),
                a.getAccountHolderName(), a.getAccountNumberMasked(),
                a.getBranchName(), a.getIfscCode(),
                bank,
                latestImport != null ? latestImport.getImportedAt() : null,
                latestImport != null ? latestImport.getStatementPeriodStart() : null,
                latestImport != null ? latestImport.getStatementPeriodEnd() : null,
                statementsCount, transactionsCount,
                "ACTIVE");
    }

    public record CreateRequest(String name, String accountType, BigDecimal balance,
                                 BigDecimal creditLimit, LocalDate dueDate, String investmentKind,
                                 String accountHolderName, String accountNumberMasked, String bankId,
                                 String branchName, String ifscCode) {}
}
