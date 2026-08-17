package com.finora.accounts;

import com.finora.entity.Account;
import com.finora.exception.ApiException;
import com.finora.repository.AccountRepository;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.StatementImportRepository.StatementMetadata;
import com.finora.repository.TransactionRepository;
import com.finora.security.OwnershipGuard;
import com.finora.service.AuditService;
import com.finora.service.BankManagementService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final StatementImportRepository statementImportRepository;
    private final TransactionRepository transactionRepository;
    private final AuditService auditService;
    private final BankManagementService bankManagementService;

    public AccountService(AccountRepository accountRepository, StatementImportRepository statementImportRepository,
                           TransactionRepository transactionRepository, AuditService auditService,
                           BankManagementService bankManagementService) {
        this.accountRepository = accountRepository;
        this.statementImportRepository = statementImportRepository;
        this.transactionRepository = transactionRepository;
        this.auditService = auditService;
        this.bankManagementService = bankManagementService;
    }

    @Transactional(readOnly = true)
    public List<AccountDto> listForUser(UUID userId) {
        List<Account> accounts = accountRepository.findByUserId(userId);

        // One query for every import this user has ever made -- used for both "last imported" /
        // "statement period" (latest per account) and "N statements" (count per account),
        // avoiding an N+1 query (one per account) for either. Metadata projection, not the
        // entity-returning finder: see StatementImportRepository.StatementMetadata's own doc
        // comment for why this method in particular (called on every account-list page view) was
        // the hottest of the six callers found still loading fileContent eagerly through it.
        Map<UUID, StatementMetadata> latestImportByAccount = new HashMap<>();
        Map<UUID, Integer> statementsCountByAccount = new HashMap<>();
        for (StatementMetadata imp : statementImportRepository.findMetadataByUserIdOrderByImportedAtDesc(userId)) {
            latestImportByAccount.putIfAbsent(imp.getAccountId(), imp);
            statementsCountByAccount.merge(imp.getAccountId(), 1, Integer::sum);
        }

        // Single grouped COUNT query for "N transactions" per account -- see
        // TransactionRepository.countByAccountForUser's own comment for why this doesn't load
        // every transaction just to count them.
        Map<UUID, Long> transactionsCountByAccount = transactionRepository.countByAccountForUser(userId).stream()
                .collect(Collectors.toMap(
                        TransactionRepository.AccountTransactionCount::getAccountId,
                        TransactionRepository.AccountTransactionCount::getCount));

        return accounts.stream()
                .map(a -> {
                    StatementMetadata latestImport = latestImportByAccount.get(a.getId());
                    return AccountDto.from(a, bankManagementService.resolve(a.getBankId()),
                            latestImport == null ? null : latestImport.getImportedAt(),
                            latestImport == null ? null : latestImport.getStatementPeriodStart(),
                            latestImport == null ? null : latestImport.getStatementPeriodEnd(),
                            statementsCountByAccount.getOrDefault(a.getId(), 0),
                            transactionsCountByAccount.getOrDefault(a.getId(), 0L));
                })
                .toList();
    }

    /**
     * Bug fix: this had no @Transactional -- the account save and its AuditService.record() call
     * are two separate writes that should either both happen or neither. AuditService.record()
     * does not swallow its own exceptions (a straight auditLogRepository.save(), no try/catch), so
     * without this, a failure in the audit write (e.g. a transient DB error) would leave the
     * account already committed while the caller still receives a 500 -- risking a duplicate
     * account being created on client retry, since nothing tells the client the mutation actually
     * succeeded. Same bug class, same fix, as BudgetService.upsert()'s own documented rationale.
     *
     * <p>Bug fix: also carried no actingAdminId, so an admin creating an account on a user's
     * behalf via AdminAccountController (support-assisted account management) was indistinguishable
     * in the audit trail from the user creating it themselves. AccountController (self-service)
     * passes its own caller's id for both userId and actingAdminId -- same "actorId" convention as
     * RelationshipService/MerchantService/RuleService, which records the id of whoever actually
     * performed the action regardless of whose data it acted on.
     */
    @Transactional
    public AccountDto create(UUID userId, AccountDto.CreateRequest req, UUID actingAdminId) {
        Account a = new Account();
        a.setUserId(userId);
        a.setName(req.name());
        a.setAccountType(parseAccountType(req.accountType()));
        a.setBalance(req.balance() != null ? req.balance() : java.math.BigDecimal.ZERO);
        a.setCreditLimit(req.creditLimit());
        a.setDueDate(req.dueDate());
        a.setInvestmentKind(req.investmentKind());
        a.setAccountHolderName(req.accountHolderName());
        a.setAccountNumberMasked(req.accountNumberMasked());
        a.setBranchName(req.branchName());
        a.setIfscCode(req.ifscCode());
        a.setPrincipalAmount(req.principalAmount());
        a.setInterestRate(req.interestRate());
        a.setMaturityDate(req.maturityDate());
        a.setMaturityAmount(req.maturityAmount());
        a.setInstallmentAmount(req.installmentAmount());
        a.setInstallmentsPaid(req.installmentsPaid());
        a.setInstallmentsTotal(req.installmentsTotal());
        // bankManagementService.resolve() checks custom banks first, then falls back to
        // BankRegistry.get() -- which itself resolves an unrecognized/blank id to OTHER rather
        // than throwing, so a manually-added account (no bank picked) or a caller that hasn't
        // sent this field yet both land here safely instead of a 400.
        a.setBankId(bankManagementService.resolve(req.bankId()).id());
        Account saved = accountRepository.save(a);
        auditService.record(userId, "ACCOUNT_CREATED", "Account", saved.getId(),
                Map.of("name", saved.getName(), "type", saved.getAccountType().name(),
                        "actorId", actingAdminId.toString()));
        return AccountDto.from(saved, bankManagementService.resolve(saved.getBankId()));
    }

    /** Bug fix: same atomicity gap as {@link #create} between the account save and the audit
     *  write, and same missing-actingAdminId gap -- see that method's own doc comment. */
    @Transactional
    public AccountDto update(UUID userId, UUID accountId, AccountDto.CreateRequest req, UUID actingAdminId) {
        Account a = getOwned(userId, accountId);
        var previousBalance = a.getBalance();
        a.setName(req.name());
        // Only overwrite when the request actually carries a value. This matters beyond just
        // "don't silently erase data set elsewhere" (the original reasoning for
        // accountHolderName/accountNumberMasked below): `balance` is NOT NULL at the DB level
        // (see V1__init_schema.sql), so unconditionally calling setBalance(req.balance()) would
        // set it to null whenever a caller sends a partial payload -- e.g. Setup.tsx's "Rename
        // Account" action, which only sends {name, accountType} -- and accountRepository.save()
        // would then fail outright with a NOT NULL constraint violation on every rename.
        if (req.balance() != null) a.setBalance(req.balance());
        if (req.creditLimit() != null) a.setCreditLimit(req.creditLimit());
        if (req.dueDate() != null) a.setDueDate(req.dueDate());
        if (req.accountHolderName() != null) a.setAccountHolderName(req.accountHolderName());
        if (req.accountNumberMasked() != null) a.setAccountNumberMasked(req.accountNumberMasked());
        if (req.branchName() != null) a.setBranchName(req.branchName());
        if (req.ifscCode() != null) a.setIfscCode(req.ifscCode());
        if (req.bankId() != null) a.setBankId(bankManagementService.resolve(req.bankId()).id());
        a.setUpdatedAt(java.time.Instant.now());
        Account saved = accountRepository.save(a);
        auditService.record(userId, "ACCOUNT_UPDATED", "Account", accountId,
                Map.of("previousBalance", previousBalance, "newBalance", saved.getBalance(),
                        "actorId", actingAdminId.toString()));
        return AccountDto.from(saved, bankManagementService.resolve(saved.getBankId()));
    }

    /** Bug fix: same atomicity gap as {@link #create} between the account (soft-)delete and the
     *  audit write, and same missing-actingAdminId gap -- see that method's own doc comment. */
    @Transactional
    public void delete(UUID userId, UUID accountId, UUID actingAdminId) {
        Account a = getOwned(userId, accountId);
        accountRepository.delete(a); // soft delete via @SQLDelete on the entity
        auditService.record(userId, "ACCOUNT_DELETED", "Account", accountId,
                Map.of("name", a.getName(), "type", a.getAccountType().name(),
                        "actorId", actingAdminId.toString()));
    }

    private Account getOwned(UUID userId, UUID accountId) {
        return OwnershipGuard.requireOwned(
                accountRepository.findById(accountId), Account::getUserId, userId, "Account");
    }

    // Bug fix: CreateRequest.accountType() has no Bean Validation and the controller doesn't use
    // @Valid, so Account.Type.valueOf() used to run directly on whatever the caller sent --
    // missing/blank/unrecognized values threw NullPointerException/IllegalArgumentException,
    // neither of which GlobalExceptionHandler has a specific handler for, so both fell through to
    // its generic Exception handler and came back as an opaque 500 instead of a real 400.
    private Account.Type parseAccountType(String accountType) {
        if (accountType == null || accountType.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "accountType is required");
        }
        try {
            return Account.Type.valueOf(accountType);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Unrecognized accountType: " + accountType);
        }
    }
}
