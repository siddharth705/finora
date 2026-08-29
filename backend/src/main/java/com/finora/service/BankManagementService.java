package com.finora.service;

import com.finora.accounts.AccountDto.BankDto;
import com.finora.entity.Bank;
import com.finora.exception.ApiException;
import com.finora.repository.AccountRepository;
import com.finora.repository.BankRepository;
import com.finora.util.AfterCommit;
import com.finora.util.BankRegistry;
import com.finora.util.PageBounds;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Merges the built-in BankRegistry (~40 verified banks, compiled into the app -- see its own
 * class comment for why it stays a static registry rather than becoming a table) with
 * admin-added custom banks (V26__custom_banks.sql) into one consistent view. Every read path that
 * resolves a bank -- the picker/search list, an account's displayed bank, transaction search-by-
 * bank-name -- goes through this service rather than BankRegistry directly, so a custom bank
 * behaves identically to a built-in one everywhere except CSV auto-detection (BankRegistry.detect,
 * used by CsvImportService, deliberately stays built-in-only -- see BankController's class comment
 * for the reasoning).
 *
 * <p>The custom-bank half of every read below goes through {@link CustomBankLookup}'s cache
 * rather than {@code bankRepository} directly -- see that class's own doc comment for why it has
 * to be a separate bean, and {@link CacheConfig} for the cache's TTL/eviction policy.
 */
@Service
public class BankManagementService {

    private final BankRepository bankRepository;
    private final AccountRepository accountRepository;
    private final AuditService auditService;
    private final CustomBankLookup customBankLookup;

    public BankManagementService(BankRepository bankRepository, AccountRepository accountRepository,
                                  AuditService auditService, CustomBankLookup customBankLookup) {
        this.bankRepository = bankRepository;
        this.accountRepository = accountRepository;
        this.auditService = auditService;
        this.customBankLookup = customBankLookup;
    }

    /** Every bank an account/statement could be assigned to -- the built-in list first (so
     *  existing UI ordering/behavior is unchanged), custom banks appended after. */
    public List<BankDto> listAll() {
        List<BankDto> result = new ArrayList<>(BankRegistry.all().stream().map(BankDto::from).toList());
        customBankLookup.all().forEach(b -> result.add(BankDto.fromCustom(b)));
        return result;
    }

    public List<BankDto> search(String query) {
        if (query == null || query.isBlank()) return listAll();
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<BankDto> result = new ArrayList<>(BankRegistry.search(query).stream().map(BankDto::from).toList());
        customBankLookup.all().stream()
                .filter(b -> b.getOfficialName().toLowerCase(Locale.ROOT).contains(q)
                        || b.getShortName().toLowerCase(Locale.ROOT).contains(q)
                        || b.getId().toLowerCase(Locale.ROOT).contains(q))
                .forEach(b -> result.add(BankDto.fromCustom(b)));
        return result;
    }

    /** Never null, same guarantee BankRegistry.get() makes -- checks custom banks first (an id a
     *  user/admin actually picked always resolves to real data), falls back to BankRegistry.get()
     *  otherwise, which itself falls back to the generic OTHER entry for anything unrecognized.
     *
     *  <p>Reads {@link CustomBankLookup#all()}'s cached list rather than
     *  {@code bankRepository.findById} -- this is the actual fix for the accounts-listing N+1
     *  named in {@code project-plan-v1.0.md} §5a: {@code AccountService.listForUser} calls this
     *  once per account, and a linear scan over a small cached list costs nothing measurable
     *  compared to a per-account database round trip. */
    public BankDto resolve(String bankId) {
        if (bankId != null) {
            Optional<Bank> custom = customBankLookup.all().stream()
                    .filter(b -> b.getId().equals(bankId))
                    .findFirst();
            if (custom.isPresent()) return BankDto.fromCustom(custom.get());
        }
        return BankDto.from(BankRegistry.get(bankId));
    }

    // --- Admin CRUD (BANK_MANAGE) -- see AdminBankController ---

    /** Admin Portal, Banks list -- paginated for UI consistency with every other admin list page,
     *  not because this table has the Subscriptions/Referrals-style unbounded-growth problem: the
     *  full custom-bank catalog is already resident in memory via {@link CustomBankLookup}'s
     *  cache (dozens to low hundreds of admin-curated rows, not a table that scales with the user
     *  base), and every other read path here deliberately depends on that whole cached list being
     *  available for a linear scan (see {@link #resolve} and {@link #search}'s own doc comments).
     *  A real DB-level {@code Page<Bank>} query would mean this one endpoint reads around the
     *  cache instead of through it, breaking the freshness guarantee
     *  {@code AdminBankControllerIT.theListEndpoint_reflectsCreateUpdateAndDelete_immediately}
     *  exists to prove -- so this pages the already-cached list in memory instead, via Spring's
     *  own {@link PageImpl}. Returns a plain {@code Page<Bank>}, not a {@link PagedResponse}, so
     *  the controller can map to {@code BankDto} first (same "service returns entities, controller
     *  maps to DTO" split this class's other read methods already use) before wrapping. */
    public Page<Bank> listCustom(int page, int size) {
        List<Bank> all = customBankLookup.all();
        int safePage = PageBounds.safePage(page);
        int safeSize = PageBounds.safeSize(size);
        int fromIndex = Math.min(safePage * safeSize, all.size());
        int toIndex = Math.min(fromIndex + safeSize, all.size());
        return new PageImpl<>(all.subList(fromIndex, toIndex), PageRequest.of(safePage, safeSize), all.size());
    }

    @Transactional
    public Bank createCustom(UUID actingAdminId, BankDto.CreateRequest req) {
        String id = req.id().toUpperCase(Locale.ROOT);
        if ("OTHER".equals(id) || BankRegistry.all().stream().anyMatch(b -> b.id().equalsIgnoreCase(id))) {
            throw new ApiException(HttpStatus.CONFLICT, "\"" + id + "\" is already used by a built-in bank.");
        }
        if (bankRepository.existsById(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "A custom bank with id \"" + id + "\" already exists.");
        }
        validateCategory(req.category());

        Bank bank = new Bank();
        bank.setId(id);
        bank.setOfficialName(req.officialName());
        bank.setShortName(req.shortName());
        if (req.colorHex() != null && !req.colorHex().isBlank()) bank.setColorHex(req.colorHex());
        if (req.initials() != null) bank.setInitials(req.initials());
        // blankToNull, not a direct pass-through: the admin form sends "" (not omitted/null) for
        // every optional field the admin left empty, and these three are genuinely-optional-vs-
        // "" distinctions that matter downstream (BankDto.category/websiteUrl/ifscPrefix are read
        // as null-checked "unknown" elsewhere, e.g. the frontend's `?? '—'` fallback display) --
        // without this, every bank created via the form would persist "" instead of null.
        bank.setCategory(blankToNull(req.category()));
        bank.setWebsiteUrl(blankToNull(req.websiteUrl()));
        bank.setIfscPrefix(blankToNull(req.ifscPrefix()));

        Bank saved = bankRepository.save(bank);
        auditService.record(actingAdminId, "BANK_CREATED", "Bank", null,
                Map.of("bankId", saved.getId(), "officialName", saved.getOfficialName()));
        // After commit, not here -- evicting mid-transaction would let a concurrent read that runs
        // before this transaction commits repopulate the cache from the pre-commit (still old) data,
        // right before the real write lands. See AfterCommit's own doc comment.
        AfterCommit.run("custom bank cache invalidation", customBankLookup::invalidate);
        return saved;
    }

    @Transactional
    public Bank updateCustom(UUID actingAdminId, String bankId, BankDto.UpdateRequest req) {
        Bank bank = requireCustom(bankId);
        if (req.officialName() != null) bank.setOfficialName(req.officialName());
        if (req.shortName() != null) bank.setShortName(req.shortName());
        if (req.colorHex() != null) bank.setColorHex(req.colorHex());
        if (req.initials() != null) bank.setInitials(req.initials());
        if (req.category() != null) {
            validateCategory(req.category());
            bank.setCategory(blankToNull(req.category()));
        }
        if (req.websiteUrl() != null) bank.setWebsiteUrl(blankToNull(req.websiteUrl()));
        if (req.ifscPrefix() != null) bank.setIfscPrefix(blankToNull(req.ifscPrefix()));
        bank.setUpdatedAt(java.time.Instant.now());

        Bank saved = bankRepository.save(bank);
        auditService.record(actingAdminId, "BANK_UPDATED", "Bank", null,
                Map.of("bankId", saved.getId()));
        AfterCommit.run("custom bank cache invalidation", customBankLookup::invalidate);
        return saved;
    }

    @Transactional
    public void deleteCustom(UUID actingAdminId, String bankId) {
        Bank bank = requireCustom(bankId);
        long inUse = accountRepository.countByBankId(bankId);
        if (inUse > 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    inUse + " account(s) are currently assigned to this bank -- reassign them before deleting it.");
        }
        bankRepository.delete(bank);
        auditService.record(actingAdminId, "BANK_DELETED", "Bank", null,
                Map.of("bankId", bankId));
        AfterCommit.run("custom bank cache invalidation", customBankLookup::invalidate);
    }

    private Bank requireCustom(String bankId) {
        return bankRepository.findById(bankId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Custom bank not found"));
    }

    private void validateCategory(String category) {
        if (category == null || category.isBlank()) return;
        try {
            BankRegistry.Category.valueOf(category);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Unknown category: " + category + " (expected PUBLIC_SECTOR, PRIVATE, SMALL_FINANCE, or FOREIGN)");
        }
    }

    /** The admin form sends "" (not null/omitted) for every optional text field left blank --
     *  without this, createCustom/updateCustom would persist "" instead of null for
     *  category/websiteUrl/ifscPrefix, which is a different value everywhere else that reads
     *  them treats as "genuinely unknown" (frontend `?? '—'` fallbacks, resolve()'s null-safe
     *  callers, ...). */
    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
