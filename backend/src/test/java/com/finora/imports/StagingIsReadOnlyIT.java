package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.repository.CategoryRepository;
import com.finora.repository.MerchantAliasRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WI3: staging writes nothing.
 *
 * <p>Bug 36, stated plainly: uploading a statement, seeing the parse is wrong and abandoning it
 * still left a permanent merchant row for every distinct description in the file. Those merchants
 * appeared in the user's Merchants page, in {@code WorkspaceDashboardService}'s totals and in the
 * admin's platform-wide counts — all derived from transactions that were never imported. Deleting
 * the import session did not remove them, because {@code ImportSessionService.deleteSession}
 * deletes the session row and nothing else.
 *
 * <p><b>Asserted by counting rows before and after, not by verifying a mock was not called.</b>
 * That is the milestone's own testing requirement, and it is the only form of this test that would
 * have caught the bug: the write happened three calls deep, in a collaborator nobody was watching.
 */
class StagingIsReadOnlyIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private MerchantRepository merchantRepository;
    @Autowired private MerchantAliasRepository merchantAliasRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private TransactionRepository transactionRepository;
    @Autowired private UserRepository userRepository;

    private User user() {
        User user = new User();
        user.setEmail("staging-readonly-it-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Staging Read Only IT User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    /** Descriptions the engine has never seen, which is exactly the case that used to create a
     *  merchant and an alias for each one. */
    private MockMultipartFile statementOfUnknownMerchants() {
        String csv = """
                Date,Description,Amount,Type
                2026-07-10,SWIGGY ORDER 4471,486.00,DEBIT
                2026-07-11,BLINKIT GROCERIES 9982,1240.50,DEBIT
                2026-07-12,ZEPTO DAILY 1123,318.00,DEBIT
                """;
        return new MockMultipartFile("file", "statement.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void stagingAStatementCreatesNoMerchantsAliasesCategoriesOrTransactions() throws Exception {
        User user = user();
        long merchantsBefore = merchantRepository.countByUserId(user.getId());
        long aliasesBefore = merchantAliasRepository.count();
        long categoriesBefore = categoryRepository.findByUserId(user.getId()).size();
        long transactionsBefore = transactionRepository.findByUserId(user.getId()).size();

        var staged = importService.parseAndStageWithSession(user.getId(), statementOfUnknownMerchants());

        // The preview genuinely parsed something -- otherwise "nothing was written" would be
        // trivially true and this test would prove nothing.
        assertThat(staged.staging().rows()).isNotEmpty();

        assertThat(merchantRepository.countByUserId(user.getId()))
                .as("staging must not invent merchants for a file that may never be imported")
                .isEqualTo(merchantsBefore);
        assertThat(merchantAliasRepository.count())
                .as("nor the aliases that point at them")
                .isEqualTo(aliasesBefore);
        assertThat(categoryRepository.findByUserId(user.getId()))
                .hasSize((int) categoriesBefore);
        assertThat(transactionRepository.findByUserId(user.getId()))
                .hasSize((int) transactionsBefore);
    }

    /**
     * The preview still has to be USEFUL, which is the constraint that makes this non-trivial.
     *
     * <p>The easy way to make staging write nothing is to stop resolving merchants at all — and
     * that would break the thing staging exists for, since the review screen's suggested category
     * comes from the learned distribution for the merchant. suggestReadOnly runs the same matching
     * in the same order as suggest; it simply does not persist the result.
     */
    @Test
    void stagingStillSuggestsCategoriesWithoutWriting() throws Exception {
        User user = user();

        var staged = importService.parseAndStageWithSession(user.getId(), statementOfUnknownMerchants());

        assertThat(staged.staging().rows())
                .as("every row still gets a category suggestion")
                .allSatisfy(row -> assertThat(row.suggestedCategory()).isNotBlank());
        assertThat(merchantRepository.countByUserId(user.getId())).isZero();
    }
}
