package com.finora.service;

import com.finora.dto.UserSettingsDto;
import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneId;
import java.util.UUID;

@Service
public class UserSettingsService {

    /** The theme values that actually exist -- see V9__theme_default_system.sql and the
     *  frontend's ThemeContext. The frontend already normalizes anything unrecognized to
     *  "system" defensively; that is a fallback, not a reason to store a value that means
     *  nothing. */
    private static final java.util.Set<String> ALLOWED_THEMES = java.util.Set.of("light", "dark", "system");

    private final UserRepository userRepository;

    public UserSettingsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public UserSettingsDto get(UUID userId) {
        User u = userRepository.findById(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        return toDto(u);
    }

    public UserSettingsDto update(UUID userId, UserSettingsDto.UpdateRequest req) {
        User u = userRepository.findById(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        if (req.lowBalanceThreshold() != null) u.setLowBalanceThreshold(req.lowBalanceThreshold());
        if (req.theme() != null) {
            // Checked against the real option list, next to the timezone check below and for the
            // same reason: the DTO's @Size bounds the string's shape, and only this knows which
            // values actually mean anything. Anything else used to be stored verbatim and handed
            // back to the client as the account's theme.
            if (!ALLOWED_THEMES.contains(req.theme())) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "'" + req.theme() + "' is not a theme option. Choose one of " + ALLOWED_THEMES + ".");
            }
            u.setTheme(req.theme());
        }
        if (req.timezone() != null) {
            // Reject up front rather than silently accepting an unparseable value -- the only
            // consumer today (DashboardService's due-date notifications) falls back safely on a
            // bad value, but validating here means that fallback is a defense-in-depth backstop,
            // not the only thing standing between a malformed request and a broken dashboard.
            try {
                ZoneId.of(req.timezone());
            } catch (Exception e) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "'" + req.timezone() + "' is not a recognized timezone.");
            }
            u.setTimezone(req.timezone());
        }
        if (req.fullName() != null) {
            String trimmed = req.fullName().trim();
            if (trimmed.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST, "Full name can't be empty.");
            u.setFullName(trimmed);
        }
        u.setUpdatedAt(Instant.now());
        User saved = userRepository.save(u);
        return toDto(saved);
    }

    private UserSettingsDto toDto(User u) {
        return new UserSettingsDto(u.getEmail(), u.getFullName(), u.getLowBalanceThreshold(), u.getTheme(),
                u.getTimezone(), u.getPhoneNumber(), u.isPhoneVerified(), u.getCreatedAt(), u.getPasswordChangedAt(),
                u.getSignInMethod());
    }
}
