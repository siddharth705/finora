package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.NewAccountRequest;
import com.finora.entity.Account;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.repository.AccountRepository;
import com.finora.repository.UserRepository;
import com.finora.service.SubscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AccountService.create's Free-tier 2-account cap (FeatureEntitlement.UNLIMITED_ACCOUNTS),
 * exercised through the REAL path a statement confirm actually takes -- ImportService.confirm()
 * resolving a NewAccountRequest into a brand-new Account -- against a real database, not
 * AccountServiceTest's mocks. AccountServiceTest already proves the gate's own logic (admin
 * bypass, entitlement bypass, boundary count); this proves the gate is actually wired into the
 * account-creation path the import pipeline uses, the same "a mocked repository would happily
 * verify a broken implementation" reasoning ProductIdentityResolutionIT gives for existing
 * end-to-end coverage of this exact confirm() method.
 */
class AccountLimitDuringConfirmIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SubscriptionService subscriptionService;

    private User createUserWithTwoAccounts() {
        User user = new User();
        user.setEmail("account-limit-confirm-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Account Limit Confirm IT User");
        user.setPhoneVerified(true);
        User saved = userRepository.save(user);
        subscriptionService.provisionFreeSubscription(saved.getId());

        for (int i = 0; i < 2; i++) {
            Account a = new Account();
            a.setUserId(saved.getId());
            a.setName("Existing Account " + i);
            a.setAccountType(Account.Type.SAVINGS);
            a.setBalance(BigDecimal.ZERO);
            accountRepository.save(a);
        }
        return saved;
    }

    private MockMultipartFile statementFile() {
        return new MockMultipartFile("file", "third-account.csv", "text/csv",
                "irrelevant-the-rows-are-supplied-directly".getBytes(StandardCharsets.UTF_8));
    }

    private ConfirmedRow row() {
        return new ConfirmedRow(LocalDate.of(2026, 7, 10), "Salary", new BigDecimal("50000.00"),
                "INCOME", "Income", true, "file", null, false, null, null);
    }

    private NewAccountRequest newThirdAccount() {
        return new NewAccountRequest("Third Bank Account", "SAVINGS", BigDecimal.ZERO, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    @Test
    void confirmingANewAccount_onFreePlan_isRejectedOnceTheUserAlreadyHasTwoAccounts() {
        User user = createUserWithTwoAccounts();
        ConfirmRequest request = new ConfirmRequest(null, List.of(row()), null, newThirdAccount(),
                null, null, null);

        assertThatThrownBy(() -> importService.confirm(user.getId(), statementFile(), request))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(ErrorCode.ACCOUNT_LIMIT_REACHED);
        assertThat(accountRepository.countByUserId(user.getId())).isEqualTo(2);
    }

    @Test
    void confirmingANewAccount_onPlusPlan_succeedsRegardlessOfExistingCount() throws Exception {
        User user = createUserWithTwoAccounts();
        subscriptionService.changePlan(user.getId(), "PLUS", "test-upgrade", user.getId());
        ConfirmRequest request = new ConfirmRequest(null, List.of(row()), null, newThirdAccount(),
                null, null, null);

        importService.confirm(user.getId(), statementFile(), request);

        assertThat(accountRepository.countByUserId(user.getId())).isEqualTo(3);
    }
}
