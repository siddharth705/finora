package com.finora.imports.ownership;

import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * docs/proposals/account-ownership-intelligence-proposal.md §3.1: which of the four
 * OwnershipMatchStatus values applies, and in what order the checks run -- account continuity
 * first (§3.1 point 2), then extraction, then the name comparison itself (§3.1 point 1).
 */
class OwnershipMatchServiceTest {

    private UserRepository userRepository;
    private StatementImportRepository statementImportRepository;
    private OwnershipMatchService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID accountId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        statementImportRepository = mock(StatementImportRepository.class);
        service = new OwnershipMatchService(userRepository, statementImportRepository);
        when(statementImportRepository.countByUserIdAndAccountId(userId, accountId)).thenReturn(0L);
    }

    private void profileNamed(String fullName) {
        User user = new User();
        ReflectionTestUtils.setField(user, "id", userId);
        user.setFullName(fullName);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    }

    @Test
    void skipsTheComparisonWhenTheAccountAlreadyHasAPriorStatement() {
        when(statementImportRepository.countByUserIdAndAccountId(userId, accountId)).thenReturn(1L);
        profileNamed("Rahul Sharma");

        assertThat(service.evaluate(userId, accountId, "Sunil Verma"))
                .isEqualTo(StatementImport.OwnershipMatchStatus.SKIPPED_EXISTING_ACCOUNT);
    }

    @Test
    void accountContinuitySkipTakesPriorityOverAMissingHolderName() {
        when(statementImportRepository.countByUserIdAndAccountId(userId, accountId)).thenReturn(1L);

        assertThat(service.evaluate(userId, accountId, null))
                .isEqualTo(StatementImport.OwnershipMatchStatus.SKIPPED_EXISTING_ACCOUNT);
    }

    @Test
    void noHolderNameExtractedOnAFirstImport() {
        profileNamed("Rahul Sharma");

        assertThat(service.evaluate(userId, accountId, null))
                .isEqualTo(StatementImport.OwnershipMatchStatus.NO_HOLDER_FOUND);
        assertThat(service.evaluate(userId, accountId, "   "))
                .isEqualTo(StatementImport.OwnershipMatchStatus.NO_HOLDER_FOUND);
    }

    @Test
    void matchingNameOnAFirstImport() {
        profileNamed("Rahul Sharma");

        assertThat(service.evaluate(userId, accountId, "Rahul Sharma"))
                .isEqualTo(StatementImport.OwnershipMatchStatus.NAME_MATCH);
    }

    @Test
    void mismatchedNameOnAFirstImport() {
        profileNamed("Rahul Sharma");

        assertThat(service.evaluate(userId, accountId, "Sunil Verma"))
                .isEqualTo(StatementImport.OwnershipMatchStatus.NAME_MISMATCH);
    }

    @Test
    void noStatusWhenTheProfileItselfHasNoNameToCompareAgainst() {
        // Nothing on the profile side to compare -- don't guess, and don't force this into
        // NO_HOLDER_FOUND, which specifically means the STATEMENT had nothing extracted.
        profileNamed("");

        assertThat(service.evaluate(userId, accountId, "Rahul Sharma")).isNull();
    }

    @Test
    void noStatusWhenTheProfileCannotBeFoundAtAll() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThat(service.evaluate(userId, accountId, "Rahul Sharma")).isNull();
    }
}
