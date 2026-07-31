package com.finora.service;

import com.finora.accounts.AccountDto.BankDto;
import com.finora.entity.Bank;
import com.finora.exception.ApiException;
import com.finora.repository.AccountRepository;
import com.finora.repository.BankRepository;
import com.finora.util.BankRegistry;
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
 */
@Service
public class BankManagementService {

    private final BankRepository bankRepository;
    private final AccountRepository accountRepository;
    private final AuditService auditService;

    public BankManagementService(BankRepository bankRepository, AccountRepository accountRepository,
                                  AuditService auditService) {
        this.bankRepository = bankRepository;
        this.accountRepository = accountRepository;
        this.auditService = auditService;
    }

    /** Every bank an account/statement could be assigned to -- the built-in list first (so
     *  existing UI ordering/behavior is unchanged), custom banks appended after. */
    @Transactional(readOnly = true)
    public List<BankDto> listAll() {
        List<BankDto> result = new ArrayList<>(BankRegistry.all().stream().map(BankDto::from).toList());
        bankRepository.findAllByOrderByOfficialNameAsc().forEach(b -> result.add(BankDto.fromCustom(b)));
        return result;
    }

    @Transactional(readOnly = true)
    public List<BankDto> search(String query) {
        if (query == null || query.isBlank()) return listAll();
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<BankDto> result = new ArrayList<>(BankRegistry.search(query).stream().map(BankDto::from).toList());
        bankRepository.findAllByOrderByOfficialNameAsc().stream()
                .filter(b -> b.getOfficialName().toLowerCase(Locale.ROOT).contains(q)
                        || b.getShortName().toLowerCase(Locale.ROOT).contains(q)
                        || b.getId().toLowerCase(Locale.ROOT).contains(q))
                .forEach(b -> result.add(BankDto.fromCustom(b)));
        return result;
    }

    /** Never null, same guarantee BankRegistry.get() makes -- checks custom banks first (an id a
     *  user/admin actually picked always resolves to real data), falls back to BankRegistry.get()
     *  otherwise, which itself falls back to the generic OTHER entry for anything unrecognized. */
    @Transactional(readOnly = true)
    public BankDto resolve(String bankId) {
        if (bankId != null) {
            Optional<Bank> custom = bankRepository.findById(bankId);
            if (custom.isPresent()) return BankDto.fromCustom(custom.get());
        }
        return BankDto.from(BankRegistry.get(bankId));
    }

    // --- Admin CRUD (BANK_MANAGE) -- see AdminBankController ---

    @Transactional(readOnly = true)
    public List<Bank> listCustom() {
        return bankRepository.findAllByOrderByOfficialNameAsc();
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
