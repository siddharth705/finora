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

    private final UserRepository userRepository;

    public UserSettingsService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public UserSettingsDto get(UUID userId) {
        User u = userRepository.findById(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        return new UserSettingsDto(u.getEmail(), u.getFullName(), u.getLowBalanceThreshold(), u.getTheme(), u.getTimezone());
    }

    public UserSettingsDto update(UUID userId, UserSettingsDto.UpdateRequest req) {
        User u = userRepository.findById(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
        if (req.lowBalanceThreshold() != null) u.setLowBalanceThreshold(req.lowBalanceThreshold());
        if (req.theme() != null) u.setTheme(req.theme());
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
        u.setUpdatedAt(Instant.now());
        User saved = userRepository.save(u);
        return new UserSettingsDto(saved.getEmail(), saved.getFullName(), saved.getLowBalanceThreshold(), saved.getTheme(), saved.getTimezone());
    }
}
