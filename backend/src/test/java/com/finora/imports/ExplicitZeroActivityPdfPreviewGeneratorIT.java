package com.finora.imports;

import com.finora.AbstractIntegrationTest;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.exception.ErrorCode;
import com.finora.imports.pdf.fixtures.PdfFixtureBuilder;
import com.finora.repository.UserRepository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Closes the gap between the two lower-level tests. {@link ExplicitZeroActivityDetectorTest} proves
 * the detector in isolation; {@link ExplicitZeroActivityRejectionTest} proves {@link
 * ExtractionCheck} chooses the right code once the flag is set. Neither proves the flag ever gets
 * set from a real parse -- this drives {@link PdfFixtureBuilder#buildExplicitZeroTransactionCountSample}
 * through the exact entry point a customer PDF upload uses ({@code
 * ImportService.parseAndStagePdfWithSession}, the same call site {@code ImportController} calls)
 * and asserts on the resulting exception, so a regression anywhere along locate -&gt;
 * buildLedgerSection -&gt; DocumentContext -&gt; ExtractionCheck is caught here rather than only in
 * a unit test that already assumes the wiring works.
 */
class ExplicitZeroActivityPdfPreviewGeneratorIT extends AbstractIntegrationTest {

    @Autowired private ImportService importService;
    @Autowired private UserRepository userRepository;

    private User user() {
        User user = new User();
        user.setEmail("explicit-zero-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("irrelevant-for-this-test");
        user.setFullName("Explicit Zero Activity User");
        user.setPhoneVerified(true);
        return userRepository.save(user);
    }

    @Test
    void aStatementDeclaringItsOwnZeroTransactionCount_isRejectedWithTheDedicatedCode() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildExplicitZeroTransactionCountSample();
        User user = user();

        assertThatThrownBy(() -> importService.parseAndStagePdfWithSession(user.getId(), "statement.pdf", pdf, null))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.type(ApiException.class))
                .extracting(ApiException::getCode)
                .isEqualTo(ErrorCode.IMPORT_NO_ACTIVITY_IN_PERIOD);
    }

    /** The negative control every fixture-driven regression test needs: a document that stages
     *  real transactions must never trip this new code, however similar its column layout. */
    @Test
    void anOrdinaryStatementWithRealTransactions_isNeverMisreadAsZeroActivity() throws Exception {
        byte[] pdf = PdfFixtureBuilder.buildSingularDepositWithdrawalColumnsSample();
        User user = user();

        var staged = importService.parseAndStagePdfWithSession(user.getId(), "statement.pdf", pdf, null);
        assertThat(staged.staging().rows()).isNotEmpty();
    }
}
