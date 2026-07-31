package com.finora.service;

import com.finora.dto.AdminDtos.FeatureFlagDto;
import com.finora.entity.FeatureFlag;
import com.finora.repository.FeatureFlagRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class FeatureFlagServiceTest {

    private FeatureFlagRepository featureFlagRepository;
    private AuditService auditService;
    private FeatureFlagService featureFlagService;
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        featureFlagRepository = mock(FeatureFlagRepository.class);
        auditService = mock(AuditService.class);
        featureFlagService = new FeatureFlagService(featureFlagRepository, auditService);
    }

    private FeatureFlag flag(String key, boolean enabled) {
        FeatureFlag f = new FeatureFlag();
        ReflectionTestUtils.setField(f, "id", UUID.randomUUID());
        f.setKey(key);
        f.setDescription("Test flag " + key);
        f.setEnabled(enabled);
        f.setUpdatedAt(Instant.now());
        return f;
    }

    @Test
    void list_sortsFlagsAlphabeticallyByKey() {
        when(featureFlagRepository.findAll()).thenReturn(
                List.of(flag("ZETA_FLAG", true), flag("ALPHA_FLAG", false)));

        List<FeatureFlagDto> result = featureFlagService.list();

        assertThat(result).extracting(FeatureFlagDto::key).containsExactly("ALPHA_FLAG", "ZETA_FLAG");
    }

    @Test
    void setEnabled_flipsTheFlagAndRecordsAnAuditEntry_whenValueActuallyChanges() {
        FeatureFlag existing = flag("RECURRING_DETECTION_ENABLED", true);
        when(featureFlagRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        FeatureFlagDto result = featureFlagService.setEnabled(adminId, existing.getId(), false);

        assertThat(result.enabled()).isFalse();
        verify(featureFlagRepository).save(existing);
        verify(auditService).record(eq(adminId), eq("FEATURE_FLAG_DISABLED"), eq("FeatureFlag"),
                eq(existing.getId()), eq(Map.of("key", "RECURRING_DETECTION_ENABLED")));
    }

    @Test
    void setEnabled_doesNotRecordAnAuditEntry_whenTheValueIsUnchanged() {
        // Toggling a flag to the value it already has shouldn't spam the audit trail with
        // no-op entries.
        FeatureFlag existing = flag("RECURRING_DETECTION_ENABLED", true);
        when(featureFlagRepository.findById(existing.getId())).thenReturn(Optional.of(existing));

        featureFlagService.setEnabled(adminId, existing.getId(), true);

        verifyNoInteractions(auditService);
    }

    @Test
    void setEnabled_throwsForAnUnknownFlagId() {
        UUID missingId = UUID.randomUUID();
        when(featureFlagRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> featureFlagService.setEnabled(adminId, missingId, false))
                .isInstanceOf(NoSuchElementException.class);
    }
}
