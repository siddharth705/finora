package com.finora.onboarding;

import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.repository.UserRepository;
import com.finora.util.EnumParsing;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class OnboardingService {

    private final UserRepository userRepository;
    private final UserFinancialFocusRepository focusRepository;

    public OnboardingService(UserRepository userRepository, UserFinancialFocusRepository focusRepository) {
        this.userRepository = userRepository;
        this.focusRepository = focusRepository;
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "User not found"));
    }

    @Transactional(readOnly = true)
    public OnboardingDto.StatusResponse getStatus(UUID userId) {
        User user = requireUser(userId);
        List<String> focus = focusRepository.findByUserId(userId).stream()
                .map(UserFinancialFocus::getFocusKey).toList();
        return new OnboardingDto.StatusResponse(user.getOnboardingCompletedAt() != null, focus);
    }

    @Transactional
    public OnboardingDto.StatusResponse setFinancialFocus(UUID userId, List<String> focusKeys) {
        requireUser(userId);
        for (String key : focusKeys) {
            EnumParsing.parse(FinancialFocus.class, key, "focusKey");
        }
        focusRepository.deleteByUserId(userId);
        for (String key : focusKeys) {
            focusRepository.save(new UserFinancialFocus(userId, key));
        }
        return getStatus(userId);
    }

    @Transactional
    public void complete(UUID userId) {
        User user = requireUser(userId);
        if (user.getOnboardingCompletedAt() == null) {
            user.setOnboardingCompletedAt(Instant.now());
        }
    }

    @Transactional
    public void reset(UUID userId) {
        User user = requireUser(userId);
        user.setOnboardingCompletedAt(null);
    }
}
