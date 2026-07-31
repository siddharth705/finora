package com.finora.service;

import com.finora.dto.WorkspaceSettingsDto;
import com.finora.entity.UserSettings;
import com.finora.exception.ApiException;
import com.finora.repository.UserSettingsRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Financial Intelligence Workspace, System Settings module.
 *
 * Scope (confirmed by product owner): exactly one real, persisted, editable setting for now --
 * auto_apply_confidence_threshold -- with every other value on the System Settings page shown
 * read-only as "coming in a future release" (a purely frontend, unpersisted label; nothing to
 * wire up here for those yet).
 *
 * This threshold is NOT currently wired into any live categorization decision.
 * ConfidenceEngine.meetsAutoApplyThreshold(int, int) exists as a hook for exactly this purpose
 * but has zero callers today -- confidence itself isn't threaded through CategorizationService's
 * Suggestion record or either write path (TransactionService.create, CsvImportService.confirm) at
 * all yet; both currently gate "needs review" purely on suggestion source ("default" vs a rule/
 * learned match), never on a numeric score. Wiring this setting into real behavior would mean
 * adding a confidence field to Suggestion and touching ~20 call sites across 3 files -- a
 * separate, larger change. This service exists so the value is real and persisted (not a fake
 * decorative slider) and ready to be read by that future change.
 */
@Service
public class WorkspaceSettingsService {

    private final UserSettingsRepository userSettingsRepository;

    public WorkspaceSettingsService(UserSettingsRepository userSettingsRepository) {
        this.userSettingsRepository = userSettingsRepository;
    }

    @Transactional(readOnly = true)
    public WorkspaceSettingsDto get(UUID userId) {
        return userSettingsRepository.findByUserId(userId)
                .map(this::toDto)
                // No row yet -- return the schema default without writing anything, so a plain
                // GET (e.g. every page load) never has a side effect. The row is only created on
                // first PUT (see update() below).
                .orElseGet(() -> new WorkspaceSettingsDto(90, null));
    }

    @Transactional
    public WorkspaceSettingsDto update(UUID userId, WorkspaceSettingsDto.UpdateRequest request) {
        if (request.autoApplyConfidenceThreshold() < 0 || request.autoApplyConfidenceThreshold() > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Confidence threshold must be between 0 and 100.");
        }
        UserSettings settings = userSettingsRepository.findByUserId(userId).orElseGet(() -> {
            UserSettings s = new UserSettings();
            s.setUserId(userId);
            return s;
        });
        settings.setAutoApplyConfidenceThreshold(request.autoApplyConfidenceThreshold());
        settings.setUpdatedAt(Instant.now());
        return toDto(userSettingsRepository.save(settings));
    }

    private WorkspaceSettingsDto toDto(UserSettings s) {
        return new WorkspaceSettingsDto(s.getAutoApplyConfidenceThreshold(), s.getUpdatedAt());
    }
}
