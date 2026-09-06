package com.finora.onboarding;

import com.finora.entity.User;
import com.finora.exception.ApiException;
import com.finora.goals.GoalRepository;
import com.finora.repository.BudgetRepository;
import com.finora.repository.ImportJobRepository;
import com.finora.repository.UserRepository;
import com.finora.util.EnumParsing;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class OnboardingService {

    private final UserRepository userRepository;
    private final UserFinancialFocusRepository focusRepository;
    private final UserChecklistEventRepository checklistEventRepository;
    private final ImportJobRepository importJobRepository;
    private final BudgetRepository budgetRepository;
    private final GoalRepository goalRepository;

    public OnboardingService(UserRepository userRepository, UserFinancialFocusRepository focusRepository,
                              UserChecklistEventRepository checklistEventRepository,
                              ImportJobRepository importJobRepository, BudgetRepository budgetRepository,
                              GoalRepository goalRepository) {
        this.userRepository = userRepository;
        this.focusRepository = focusRepository;
        this.checklistEventRepository = checklistEventRepository;
        this.importJobRepository = importJobRepository;
        this.budgetRepository = budgetRepository;
        this.goalRepository = goalRepository;
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
        // Bug fix: an omitted/null "focusKeys" field (e.g. a body of `{}`) deserializes the record
        // component to null, and the loop below ran straight off that null with no handler for
        // NullPointerException in GlobalExceptionHandler -- an opaque 500 instead of the same 400
        // every other missing-required-field case gets. Same bug class EnumParsing's own doc
        // comment describes for unchecked IllegalArgumentException.
        if (focusKeys == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "focusKeys is required");
        }
        for (String key : focusKeys) {
            EnumParsing.parse(FinancialFocus.class, key, "focusKey");
        }
        focusRepository.deleteByUserId(userId);
        // Deduplicated (not just validated) before inserting: user_financial_focus has a
        // UNIQUE(user_id, focus_key) constraint (V161), so the same key appearing twice in one
        // request would otherwise hit that constraint mid-transaction and surface as a generic 409
        // "conflicts with an existing record" -- misleading for what's actually just a redundant
        // selection, not a real conflict. The frontend's own toggle-based multi-select can't
        // produce this today, but the API itself shouldn't depend on that.
        Set<String> distinctKeys = new LinkedHashSet<>(focusKeys);
        for (String key : distinctKeys) {
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

    @Transactional(readOnly = true)
    public OnboardingDto.ChecklistResponse getChecklist(UUID userId) {
        User user = requireUser(userId);
        Set<String> explicitDone = checklistEventRepository.findByUserId(userId).stream()
                .map(UserChecklistEvent::getItemKey).collect(Collectors.toSet());

        boolean profileComplete = user.getFullName() != null && !user.getFullName().isBlank()
                && user.isEmailVerified()
                && (user.getPhoneNumber() == null || user.isPhoneVerified());
        boolean importedStatement = !importJobRepository
                .findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 1)).isEmpty();
        boolean createdBudget = !budgetRepository.findByUserId(userId).isEmpty();
        boolean createdGoal = !goalRepository.findByUserId(userId).isEmpty();

        Map<ChecklistItemKey, Boolean> completedByKey = new EnumMap<>(ChecklistItemKey.class);
        completedByKey.put(ChecklistItemKey.COMPLETE_PROFILE, profileComplete);
        completedByKey.put(ChecklistItemKey.IMPORT_STATEMENT, importedStatement);
        completedByKey.put(ChecklistItemKey.REVIEW_TRANSACTIONS, explicitDone.contains("REVIEW_TRANSACTIONS"));
        completedByKey.put(ChecklistItemKey.CREATE_BUDGET, createdBudget);
        completedByKey.put(ChecklistItemKey.CREATE_GOAL, createdGoal);
        completedByKey.put(ChecklistItemKey.VIEW_INSIGHTS, explicitDone.contains("VIEW_INSIGHTS"));

        List<OnboardingDto.ChecklistItemDto> items = completedByKey.entrySet().stream()
                .map(e -> new OnboardingDto.ChecklistItemDto(e.getKey().name(), e.getValue()))
                .toList();
        int completedCount = (int) items.stream().filter(OnboardingDto.ChecklistItemDto::completed).count();

        return new OnboardingDto.ChecklistResponse(items, completedCount, items.size());
    }

    @Transactional
    public void completeChecklistItem(UUID userId, String itemKey) {
        ChecklistItemKey key = EnumParsing.parse(ChecklistItemKey.class, itemKey, "itemKey");
        if (!key.isExplicit()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    key + " is derived automatically and can't be marked complete directly");
        }
        // key.name() rather than the raw itemKey: EnumParsing accepts case-insensitive/untrimmed
        // input, but getChecklist()'s explicitDone lookup compares against the canonical enum
        // name -- storing the raw input would silently break that match for any caller that
        // doesn't send exact-uppercase.
        String canonicalKey = key.name();
        if (!checklistEventRepository.existsByUserIdAndItemKey(userId, canonicalKey)) {
            checklistEventRepository.save(new UserChecklistEvent(userId, canonicalKey));
        }
    }
}
