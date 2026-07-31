package com.finora.service;

import com.finora.dto.AdminDtos.SearchResultDto;
import com.finora.entity.Bank;
import com.finora.entity.CategoryRule;
import com.finora.entity.User;
import com.finora.repository.BankRepository;
import com.finora.repository.CategoryRuleRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Global Search -- mocked-repository unit test covering the fan-out across all four entity
 *  types and the empty-query short-circuit. Each sub-search's own filtering logic (LIKE clauses,
 *  scope) is exercised at the repository/JPQL level elsewhere; this test cares about the
 *  aggregation and mapping into a single unified SearchResultDto shape. */
class AdminSearchServiceTest {

    private UserRepository userRepository;
    private MerchantRepository merchantRepository;
    private BankRepository bankRepository;
    private CategoryRuleRepository categoryRuleRepository;
    private AdminSearchService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        merchantRepository = mock(MerchantRepository.class);
        bankRepository = mock(BankRepository.class);
        categoryRuleRepository = mock(CategoryRuleRepository.class);
        service = new AdminSearchService(userRepository, merchantRepository, bankRepository, categoryRuleRepository);
    }

    private User user(String fullName, String email) {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
        u.setFullName(fullName);
        u.setEmail(email);
        return u;
    }

    private Bank bank(String id, String officialName, String shortName) {
        Bank b = new Bank();
        b.setId(id);
        b.setOfficialName(officialName);
        b.setShortName(shortName);
        return b;
    }

    private CategoryRule rule(String comparisonValue, String actionValue) {
        CategoryRule r = new CategoryRule();
        ReflectionTestUtils.setField(r, "id", UUID.randomUUID());
        r.setScope(CategoryRule.Scope.GLOBAL);
        r.setField(CategoryRule.Field.DESCRIPTION);
        r.setOperator(CategoryRule.Operator.CONTAINS);
        r.setComparisonValue(comparisonValue);
        r.setActionType(CategoryRule.ActionType.ASSIGN_CATEGORY);
        r.setActionValue(actionValue);
        r.setEnabled(true);
        return r;
    }

    @Test
    void search_blankQueryReturnsEmptyWithoutTouchingAnyRepository() {
        assertThat(service.search("   ")).isEmpty();
        assertThat(service.search(null)).isEmpty();
        verify(userRepository, never()).search(any(), any(), any());
    }

    @Test
    void search_combinesResultsAcrossAllFourEntityTypes() {
        Page<User> userPage = new PageImpl<>(List.of(user("Amazon Shopper", "amazon.shopper@example.com")));
        when(userRepository.search(eq("amazon"), isNull(), any())).thenReturn(userPage);
        when(merchantRepository.searchDistinctCanonicalNames(eq("amazon"), any())).thenReturn(List.of("Amazon"));
        when(bankRepository.searchByName(eq("amazon"), any())).thenReturn(List.of());
        when(categoryRuleRepository.findByScopeOrderByPriorityAsc(CategoryRule.Scope.GLOBAL))
                .thenReturn(List.of(rule("amazon", "Shopping"), rule("uber", "Transport")));

        List<SearchResultDto> results = service.search("amazon");

        assertThat(results).extracting(SearchResultDto::type).containsExactlyInAnyOrder("user", "merchant", "rule");
        var userResult = results.stream().filter(r -> r.type().equals("user")).findFirst().orElseThrow();
        assertThat(userResult.title()).isEqualTo("Amazon Shopper");
        assertThat(userResult.link()).startsWith("/users/");

        var merchantResult = results.stream().filter(r -> r.type().equals("merchant")).findFirst().orElseThrow();
        assertThat(merchantResult.id()).isEqualTo("Amazon");
        assertThat(merchantResult.link()).isEqualTo("/merchants");

        var ruleResult = results.stream().filter(r -> r.type().equals("rule")).findFirst().orElseThrow();
        assertThat(ruleResult.title()).contains("amazon");
        assertThat(ruleResult.link()).isEqualTo("/rules");
    }

    @Test
    void search_bankResultUsesOfficialNameAsTitleAndShortNameAsSubtitle() {
        when(userRepository.search(anyString(), isNull(), any())).thenReturn(Page.empty());
        when(merchantRepository.searchDistinctCanonicalNames(anyString(), any())).thenReturn(List.of());
        when(bankRepository.searchByName(eq("hdfc"), any())).thenReturn(List.of(bank("hdfc", "HDFC Bank Ltd", "HDFC")));
        when(categoryRuleRepository.findByScopeOrderByPriorityAsc(CategoryRule.Scope.GLOBAL)).thenReturn(List.of());

        List<SearchResultDto> results = service.search("hdfc");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).type()).isEqualTo("bank");
        assertThat(results.get(0).title()).isEqualTo("HDFC Bank Ltd");
        assertThat(results.get(0).subtitle()).isEqualTo("HDFC");
    }

    @Test
    void search_globalRuleFilteringIsCaseInsensitiveAndMatchesActionValueToo() {
        when(userRepository.search(anyString(), isNull(), any())).thenReturn(Page.empty());
        when(merchantRepository.searchDistinctCanonicalNames(anyString(), any())).thenReturn(List.of());
        when(bankRepository.searchByName(anyString(), any())).thenReturn(List.of());
        when(categoryRuleRepository.findByScopeOrderByPriorityAsc(CategoryRule.Scope.GLOBAL))
                .thenReturn(List.of(rule("some description", "Groceries"), rule("other description", "Transport")));

        List<SearchResultDto> results = service.search("GROCER");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).subtitle()).contains("Groceries");
    }
}
