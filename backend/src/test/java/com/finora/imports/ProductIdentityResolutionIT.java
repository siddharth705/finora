package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.ConfirmResponse;
import com.finora.dto.ImportDto.NewAccountRequest;
import com.finora.entity.Account;
import com.finora.entity.Transaction;
import com.finora.entity.User;
import com.finora.repository.AccountRepository;
import com.finora.repository.MerchantLearningEventRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ProductIdentityResolver audit, Phase 2. Proves the MATCHED path end-to-end against a real
 * database, not just at the resolver's own unit-test level.
 *
 * <p>{@code ProductIdentityResolverTest} already proves {@code resolve()} returns
 * {@code Resolution.MATCHED} for the right inputs. What had no coverage anywhere -- confirmed
 * during the audit by reading {@code ImportServiceAskOnceTest}'s setup, where
 * {@code accountRepository.findByUserId(any())} is never stubbed and so silently returns an empty
 * list -- is whether {@code ImportService.resolveTargetAccount} actually threads a MATCHED result
 * through {@code confirm()} into the right account's transactions and balance, with no duplicate
 * account created. A mocked repository would happily verify whichever behaviour the implementation
 * happened to have, including a broken one; this is written against a real database on purpose,
 * the same reasoning {@link ImportAccountBalanceIT} gives for doing the same thing with Bug 17.
 */
class ProductIdentityResolutionIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private MerchantLearningEventRepository learningEventRepository;

    /** See {@link ImportAccountBalanceIT#removeQueuedLearningEvents()} for why this exists. */
    private final List<UUID> createdUserIds = new java.util.ArrayList<>();

    @AfterEach
    void removeQueuedLearningEvents() {
        if (createdUserIds.isEmpty()) return;
        learningEventRepository.deleteAll(learningEventRepository.findAll().stream()
                .filter(e -> createdUserIds.contains(e.getUserId()))
                .toList());
        createdUserIds.clear();
    }

    private User user() {
        User user = new User();
        user.setEmail("product-identity-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Product Identity IT User");
        user.setPhoneVerified(true);
        User saved = userRepository.save(user);
        createdUserIds.add(saved.getId());
        return saved;
    }

    private MockMultipartFile statementFile() {
        return new MockMultipartFile("file", "statement.csv", "text/csv",
                "irrelevant-the-rows-are-supplied-directly".getBytes(StandardCharsets.UTF_8));
    }

    private ConfirmedRow row(String description, String amount, String type) {
        return new ConfirmedRow(LocalDate.of(2026, 7, 10), description, new BigDecimal(amount), type,
                "Other", true, "rule", null, false, null, null);
    }

    @Test
    @DisplayName("MATCHED: re-importing the same HDFC savings account attaches to it, not a new one")
    void reimportingTheSameAccountAttachesToTheExistingOneRatherThanCreatingADuplicate() throws Exception {
        User user = user();
        String sharedIdentityHash = "test-strong-key-" + UUID.randomUUID();

        // The account the user already holds in Finora, exactly as ImportService.resolveTargetAccount
        // leaves it after a first import: bankId/productType/productIdentityHash all stamped (see
        // ImportService.java's "Stamp the identity so the NEXT import of this product recognises it").
        Account existing = new Account();
        existing.setUserId(user.getId());
        existing.setName("HDFC Savings");
        existing.setAccountType(Account.Type.SAVINGS);
        existing.setBalance(new BigDecimal("1000.00"));
        existing.setBankId("HDFC");
        existing.setProductType("SAVINGS");
        existing.setProductIdentityHash(sharedIdentityHash);
        existing.setAccountNumberMasked("1234");
        existing = accountRepository.save(existing);

        assertThat(accountRepository.countByUserId(user.getId())).isEqualTo(1);

        // A second statement for the SAME account: same bank, same productIdentityHash -- exactly
        // what the client echoes back from staging for a re-recognised product (see
        // ProductIdentity.stored's caller, ImportService.resolveTargetAccount). No existingAccountId
        // is given -- this is the "new account" branch specifically so it has to go through the
        // resolver to find the match, the same as a real re-import of a bank-detected statement.
        NewAccountRequest newAccount = new NewAccountRequest(
                "HDFC Savings", "SAVINGS", new BigDecimal("1000.00"), null, null,
                null, "1234", "HDFC", null, null,
                "SAVINGS", sharedIdentityHash,
                null, null, null, null, null, null, null);
        ConfirmRequest request = new ConfirmRequest(null,
                List.of(row("SALARY", "500.00", "INCOME"), row("METRO FARE", "45.00", "EXPENSE")),
                null, newAccount, null, null, null);

        ConfirmResponse response = importService.confirm(user.getId(), statementFile(), request);

        assertThat(accountRepository.countByUserId(user.getId()))
                .as("MATCHED must attach to the existing account, never create a second one")
                .isEqualTo(1);
        assertThat(response.accountsCreated())
                .as("no new account was created, so nothing should be reported as created")
                .isEmpty();

        UUID existingAccountId = existing.getId();
        List<Transaction> persisted = transactionRepository.findByUserId(user.getId());
        assertThat(persisted)
                .as("both rows must land on the existing account, not float unattached or attach "
                        + "to some other row")
                .hasSize(2)
                .allMatch(t -> existingAccountId.equals(t.getAccountId()));

        assertThat(accountRepository.findById(existingAccountId).orElseThrow().getBalance())
                .as("the existing account's balance must move by the imported rows' net effect, "
                        + "the same as any other import into a known account")
                .isEqualByComparingTo("1455.00");
    }
}
