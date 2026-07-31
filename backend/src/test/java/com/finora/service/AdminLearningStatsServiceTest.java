package com.finora.service;

import com.finora.dto.AdminDtos.LearningPlatformStatsDto;
import com.finora.entity.MerchantLearningAudit;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.repository.MerchantLearningAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Platform-wide Learning Engine stats -- mocked-repository unit tests, same pattern as
 *  WorkspaceDashboardServiceTest. Proves platformActionCounts() rows get mapped to the right
 *  named fields (not just summed blindly) and that the month-bucketed trend only counts
 *  LEARNED/CORRECTED, same exclusion AnalyticsService.learningGrowth() applies. */
class AdminLearningStatsServiceTest {

    private MerchantCategoryLearningRepository learningRepository;
    private MerchantLearningAuditRepository auditRepository;
    private AdminLearningStatsService service;

    @BeforeEach
    void setUp() {
        learningRepository = mock(MerchantCategoryLearningRepository.class);
        auditRepository = mock(MerchantLearningAuditRepository.class);
        service = new AdminLearningStatsService(learningRepository, auditRepository);
    }

    private MerchantLearningAudit entry(MerchantLearningAudit.Action action) {
        MerchantLearningAudit a = new MerchantLearningAudit();
        a.setAction(action);
        return a;
    }

    private MerchantLearningAudit entryInMonth(MerchantLearningAudit.Action action, int year, int month) {
        MerchantLearningAudit a = entry(action);
        Instant createdAt = LocalDate.of(year, month, 15).atStartOfDay(ZoneOffset.UTC).toInstant();
        ReflectionTestUtils.setField(a, "createdAt", createdAt);
        return a;
    }

    @Test
    void platformStats_mapsEachActionRowToItsOwnNamedField_notJustOneCombinedTotal() {
        when(learningRepository.count()).thenReturn(42L);
        when(auditRepository.platformActionCounts()).thenReturn(List.of(
                new Object[]{MerchantLearningAudit.Action.LEARNED, 10L},
                new Object[]{MerchantLearningAudit.Action.CORRECTED, 4L},
                new Object[]{MerchantLearningAudit.Action.RESET, 2L},
                new Object[]{MerchantLearningAudit.Action.UNDONE, 1L}
        ));
        when(auditRepository.findAll()).thenReturn(List.of());

        LearningPlatformStatsDto stats = service.platformStats();

        assertThat(stats.learnedMerchantPairs()).isEqualTo(42L);
        assertThat(stats.correctedCount()).isEqualTo(4L);
        assertThat(stats.resetCount()).isEqualTo(2L);
        // totalConfirmations = LEARNED + CORRECTED only, mirroring the per-user Summary DTO's
        // own definition -- UNDONE/RESET/MERGED never counted as confirmations there either.
        assertThat(stats.totalConfirmations()).isEqualTo(14L);
    }

    @Test
    void platformStats_toleratesAnEmptyPlatform_ratherThanThrowing() {
        when(learningRepository.count()).thenReturn(0L);
        when(auditRepository.platformActionCounts()).thenReturn(List.of());
        when(auditRepository.findAll()).thenReturn(List.of());

        LearningPlatformStatsDto stats = service.platformStats();

        assertThat(stats.learnedMerchantPairs()).isZero();
        assertThat(stats.totalConfirmations()).isZero();
        assertThat(stats.trend()).isEmpty();
    }

    @Test
    void trend_onlyCountsLearnedAndCorrected_ignoringUndoneResetMerged() {
        when(learningRepository.count()).thenReturn(0L);
        when(auditRepository.platformActionCounts()).thenReturn(List.of());
        when(auditRepository.findAll()).thenReturn(List.of(
                entry(MerchantLearningAudit.Action.LEARNED),
                entry(MerchantLearningAudit.Action.CORRECTED),
                entry(MerchantLearningAudit.Action.UNDONE),
                entry(MerchantLearningAudit.Action.RESET),
                entry(MerchantLearningAudit.Action.MERGED)
        ));

        LearningPlatformStatsDto stats = service.platformStats();

        assertThat(stats.trend()).hasSize(1);
        assertThat(stats.trend().get(0).learnedCount()).isEqualTo(1);
        assertThat(stats.trend().get(0).correctedCount()).isEqualTo(1);
    }

    /** Bug fix regression test: a month with zero LEARNED/CORRECTED activity sitting BETWEEN two
     *  active months must still appear in the trend as a real zero point, not be skipped --
     *  otherwise a line chart built on this data would jump straight over the quiet month instead
     *  of showing the platform actually went quiet, same gap-filling AnalyticsService
     *  .learningGrowth() already does per-user. */
    @Test
    void trend_fillsAQuietMonthBetweenTwoActiveMonthsWithAZeroPoint_ratherThanSkippingIt() {
        when(learningRepository.count()).thenReturn(0L);
        when(auditRepository.platformActionCounts()).thenReturn(List.of());
        when(auditRepository.findAll()).thenReturn(List.of(
                entryInMonth(MerchantLearningAudit.Action.LEARNED, 2026, 1),
                // February has no activity at all -- must still show up as a zero point.
                entryInMonth(MerchantLearningAudit.Action.CORRECTED, 2026, 3)
        ));

        LearningPlatformStatsDto stats = service.platformStats();

        assertThat(stats.trend()).extracting("month").containsExactly("2026-01", "2026-02", "2026-03");
        assertThat(stats.trend().get(1).learnedCount()).isZero();
        assertThat(stats.trend().get(1).correctedCount()).isZero();
    }
}
