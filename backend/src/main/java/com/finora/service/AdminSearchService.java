package com.finora.service;

import com.finora.dto.AdminDtos.SearchResultDto;
import com.finora.entity.CategoryRule;
import com.finora.repository.BankRepository;
import com.finora.repository.CategoryRuleRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.UserRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Global Search (Admin Portal Phase 2) -- one query fanned out across every admin entity that has
 * a real destination page today: Users, the platform Merchant catalog, the Bank registry, and
 * Global Rules. Deliberately excludes Transactions and Statement Imports: neither has a
 * standalone admin page today (both only ever appear as a section within a specific user's
 * UserDetail view), so a search result pointing at "transaction abc123" would have nowhere useful
 * to land -- add that once such a page exists, not before.
 *
 * Each sub-search is capped at PER_TYPE_LIMIT and reuses an existing repository query wherever
 * one already existed (UserRepository.search, same as the admin Users directory). Bank and
 * Merchant needed one new LIKE-based method each (BankRepository.searchByName,
 * MerchantRepository.searchDistinctCanonicalNames). Global Rules is small enough (platform-wide,
 * not per-user -- typically a handful to a few dozen rows) that an in-memory filter over the
 * existing findByScopeOrderByPriorityAsc list is the right level of effort, the same "simple
 * indexed counts/lists, not a new reporting subsystem" discipline AdminStatsService documents for
 * the rest of this admin surface.
 *
 * Merchant results have no single canonical entity id (see MerchantRepository's class comment --
 * there is no shared/canonical merchant table, only per-user rows that happen to share a name),
 * so their "id" is the canonicalName itself and every merchant result links to the platform
 * catalog page (/merchants) rather than a specific record.
 */
@Service
public class AdminSearchService {

    private static final int PER_TYPE_LIMIT = 5;

    private final UserRepository userRepository;
    private final MerchantRepository merchantRepository;
    private final BankRepository bankRepository;
    private final CategoryRuleRepository categoryRuleRepository;

    public AdminSearchService(UserRepository userRepository, MerchantRepository merchantRepository,
                               BankRepository bankRepository, CategoryRuleRepository categoryRuleRepository) {
        this.userRepository = userRepository;
        this.merchantRepository = merchantRepository;
        this.bankRepository = bankRepository;
        this.categoryRuleRepository = categoryRuleRepository;
    }

    @Transactional(readOnly = true)
    public List<SearchResultDto> search(String rawQuery) {
        String q = rawQuery == null ? "" : rawQuery.trim();
        if (q.isEmpty()) return List.of();

        List<SearchResultDto> results = new ArrayList<>();
        results.addAll(searchUsers(q));
        results.addAll(searchMerchants(q));
        results.addAll(searchBanks(q));
        results.addAll(searchGlobalRules(q));
        return results;
    }

    private List<SearchResultDto> searchUsers(String q) {
        return userRepository.search(q, null, PageRequest.of(0, PER_TYPE_LIMIT)).getContent().stream()
                .map(u -> new SearchResultDto("user", u.getId().toString(), u.getFullName(), u.getEmail(),
                        "/users/" + u.getId()))
                .toList();
    }

    private List<SearchResultDto> searchMerchants(String q) {
        return merchantRepository.searchDistinctCanonicalNames(q, PageRequest.of(0, PER_TYPE_LIMIT)).stream()
                .map(name -> new SearchResultDto("merchant", name, name, "Merchant catalog", "/merchants"))
                .toList();
    }

    private List<SearchResultDto> searchBanks(String q) {
        return bankRepository.searchByName(q, PageRequest.of(0, PER_TYPE_LIMIT)).stream()
                .map(b -> new SearchResultDto("bank", b.getId(), b.getOfficialName(), b.getShortName(), "/banks"))
                .toList();
    }

    private List<SearchResultDto> searchGlobalRules(String q) {
        String needle = q.toLowerCase(Locale.ROOT);
        return categoryRuleRepository.findByScopeOrderByPriorityAsc(CategoryRule.Scope.GLOBAL).stream()
                .filter(r -> matchesRule(r, needle))
                .limit(PER_TYPE_LIMIT)
                .map(r -> new SearchResultDto("rule", r.getId().toString(),
                        r.getField() + " " + r.getOperator() + " \"" + r.getComparisonValue() + "\"",
                        r.getActionType() + (r.getActionValue() != null ? ": " + r.getActionValue() : ""),
                        "/rules"))
                .toList();
    }

    private boolean matchesRule(CategoryRule r, String needle) {
        return (r.getComparisonValue() != null && r.getComparisonValue().toLowerCase(Locale.ROOT).contains(needle))
                || (r.getActionValue() != null && r.getActionValue().toLowerCase(Locale.ROOT).contains(needle));
    }
}
