package com.finora.imports.ownership;

import com.finora.entity.StatementImport;
import com.finora.entity.User;
import com.finora.repository.StatementImportRepository;
import com.finora.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * docs/proposals/account-ownership-intelligence-proposal.md §3.1 -- decides which of the four
 * {@link StatementImport.OwnershipMatchStatus} values applies to one confirm, in the order the
 * design doc itself specifies: account continuity first (§3.1 point 2, cheapest and highest
 * priority -- an established account is never re-warned on), then whether anything was extracted to
 * compare at all, then the name comparison itself ({@link HolderNameMatcher}, deliberately the
 * weakest signal -- see the doc's §5 note on why name similarity ranks below account continuity).
 *
 * <p>Called once per confirm, before the new {@code StatementImport} row is saved -- see {@code
 * ImportService.persistSection}, the sole caller.
 */
@Component
public class OwnershipMatchService {

    private final UserRepository userRepository;
    private final StatementImportRepository statementImportRepository;

    public OwnershipMatchService(UserRepository userRepository,
                                  StatementImportRepository statementImportRepository) {
        this.userRepository = userRepository;
        this.statementImportRepository = statementImportRepository;
    }

    /**
     * @return the status to persist, or {@code null} when there is genuinely nothing to compare
     * (the confirming user's own profile has no name on file) -- deliberately not forced into
     * {@code NO_HOLDER_FOUND}, which specifically means the STATEMENT had nothing extracted, not
     * that the profile side of the comparison was unavailable.
     */
    public StatementImport.OwnershipMatchStatus evaluate(UUID userId, UUID accountId, String extractedHolderName) {
        if (statementImportRepository.countByUserIdAndAccountId(userId, accountId) > 0) {
            return StatementImport.OwnershipMatchStatus.SKIPPED_EXISTING_ACCOUNT;
        }
        if (extractedHolderName == null || extractedHolderName.isBlank()) {
            return StatementImport.OwnershipMatchStatus.NO_HOLDER_FOUND;
        }
        String profileName = userRepository.findById(userId).map(User::getFullName).orElse(null);
        if (profileName == null || profileName.isBlank()) {
            return null;
        }
        return HolderNameMatcher.isLikelyMatch(extractedHolderName, profileName)
                ? StatementImport.OwnershipMatchStatus.NAME_MATCH
                : StatementImport.OwnershipMatchStatus.NAME_MISMATCH;
    }
}
