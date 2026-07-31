package com.finora.service;

import com.finora.dto.WorkspaceSettingsDto;
import com.finora.entity.UserSettings;
import com.finora.exception.ApiException;
import com.finora.repository.UserSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class WorkspaceSettingsServiceTest {

    private UserSettingsRepository userSettingsRepository;
    private WorkspaceSettingsService service;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        userSettingsRepository = mock(UserSettingsRepository.class);
        service = new WorkspaceSettingsService(userSettingsRepository);
    }

    @Test
    void get_returnsTheSchemaDefault_whenNoRowExistsYet_withoutWritingAnything() {
        when(userSettingsRepository.findByUserId(userId)).thenReturn(Optional.empty());

        WorkspaceSettingsDto dto = service.get(userId);

        assertThat(dto.autoApplyConfidenceThreshold()).isEqualTo(90);
        assertThat(dto.updatedAt()).isNull();
        verify(userSettingsRepository, never()).save(any());
    }

    @Test
    void get_returnsThePersistedValue_whenARowExists() {
        UserSettings s = new UserSettings();
        s.setUserId(userId);
        s.setAutoApplyConfidenceThreshold(75);
        when(userSettingsRepository.findByUserId(userId)).thenReturn(Optional.of(s));

        WorkspaceSettingsDto dto = service.get(userId);

        assertThat(dto.autoApplyConfidenceThreshold()).isEqualTo(75);
    }

    @Test
    void update_createsARow_onFirstSave() {
        when(userSettingsRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(userSettingsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WorkspaceSettingsDto dto = service.update(userId, new WorkspaceSettingsDto.UpdateRequest(80));

        assertThat(dto.autoApplyConfidenceThreshold()).isEqualTo(80);
        verify(userSettingsRepository).save(argThat(s -> s.getUserId().equals(userId) && s.getAutoApplyConfidenceThreshold() == 80));
    }

    @Test
    void update_overwritesAnExistingRow_ratherThanCreatingASecondOne() {
        UserSettings existing = new UserSettings();
        existing.setUserId(userId);
        existing.setAutoApplyConfidenceThreshold(90);
        when(userSettingsRepository.findByUserId(userId)).thenReturn(Optional.of(existing));
        when(userSettingsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.update(userId, new WorkspaceSettingsDto.UpdateRequest(50));

        verify(userSettingsRepository, times(1)).save(any());
        assertThat(existing.getAutoApplyConfidenceThreshold()).isEqualTo(50);
    }

    @Test
    void update_rejectsAThresholdAbove100() {
        assertThatThrownBy(() -> service.update(userId, new WorkspaceSettingsDto.UpdateRequest(101)))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(userSettingsRepository);
    }

    @Test
    void update_rejectsANegativeThreshold() {
        assertThatThrownBy(() -> service.update(userId, new WorkspaceSettingsDto.UpdateRequest(-1)))
                .isInstanceOf(ApiException.class);
        verifyNoInteractions(userSettingsRepository);
    }

    @Test
    void update_acceptsTheBoundaryValuesZeroAndOneHundred() {
        when(userSettingsRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(userSettingsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.update(userId, new WorkspaceSettingsDto.UpdateRequest(0)).autoApplyConfidenceThreshold()).isEqualTo(0);
        assertThat(service.update(userId, new WorkspaceSettingsDto.UpdateRequest(100)).autoApplyConfidenceThreshold()).isEqualTo(100);
    }
}
