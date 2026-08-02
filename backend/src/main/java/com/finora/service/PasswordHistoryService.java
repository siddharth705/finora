package com.finora.service;

import com.finora.entity.PasswordHistory;
import com.finora.exception.ApiException;
import com.finora.repository.PasswordHistoryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Blocks the common "Password1 -> Password2 -> Password1" cycle -- shared by every path that sets
 * a user's password (registration, AuthService.resetPassword(), PasswordChangeService.complete())
 * rather than copy-pasted into each, since this involves real repository state, not just a small
 * pure comparison (contrast with phoneNumbersMatch(), which IS deliberately duplicated in both
 * services per its own doc comment).
 */
@Service
public class PasswordHistoryService {

    private static final int HISTORY_LIMIT = 5;

    private final PasswordHistoryRepository passwordHistoryRepository;
    private final PasswordEncoder passwordEncoder;

    public PasswordHistoryService(PasswordHistoryRepository passwordHistoryRepository, PasswordEncoder passwordEncoder) {
        this.passwordHistoryRepository = passwordHistoryRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /** Throws if rawNewPassword matches any of the user's last {@value #HISTORY_LIMIT} passwords.
     *  Callers still separately check the new password against the CURRENT hash (a distinct,
     *  slightly different user-facing message) -- this only covers older, since-replaced ones. */
    public void rejectIfRecentlyUsed(UUID userId, String rawNewPassword) {
        List<PasswordHistory> recent = passwordHistoryRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .limit(HISTORY_LIMIT)
                .toList();
        for (PasswordHistory entry : recent) {
            if (passwordEncoder.matches(rawNewPassword, entry.getPasswordHash())) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "You've used this password recently. Choose a password you haven't used before.");
            }
        }
    }

    /** Records a newly-set password hash and prunes anything beyond the last
     *  {@value #HISTORY_LIMIT} for this user -- called after a password is actually persisted
     *  (registration, reset, or change), never speculatively. */
    public void record(UUID userId, String passwordHash) {
        PasswordHistory entry = new PasswordHistory();
        entry.setUserId(userId);
        entry.setPasswordHash(passwordHash);
        passwordHistoryRepository.save(entry);

        List<PasswordHistory> all = passwordHistoryRepository.findByUserIdOrderByCreatedAtDesc(userId);
        if (all.size() > HISTORY_LIMIT) {
            passwordHistoryRepository.deleteAll(all.subList(HISTORY_LIMIT, all.size()));
        }
    }
}
