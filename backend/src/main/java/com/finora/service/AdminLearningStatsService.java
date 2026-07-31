package com.finora.service;

import com.finora.dto.AdminDtos.LearningPlatformStatsDto;
import com.finora.dto.AnalyticsDto;
import com.finora.entity.MerchantLearningAudit;
import com.finora.repository.MerchantCategoryLearningRepository;
import com.finora.repository.MerchantLearningAuditRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Platform-wide Learning Engine stats for the admin console -- the aggregate view that has no
 * self-service equivalent (a single user only ever sees their own MerchantLearningService.summary()).
 * Same "cheap live aggregate, not a new reporting subsystem" shape as AdminMerchantStatsService:
 * one grouped COUNT query for the totals, plus a month-bucketed trend that reuses
 * AnalyticsService.learningGrowth()'s exact bucketing logic (see that method's own doc comment)
 * but over every user's MerchantLearningAudit rows instead of one.
 */
@Service
public class AdminLearningStatsService {

    private final MerchantCategoryLearningRepository learningRepository;
    private final MerchantLearningAuditRepository auditRepository;

    public AdminLearningStatsService(MerchantCategoryLearningRepository learningRepository,
                                      MerchantLearningAuditRepository auditRepository) {
        this.learningRepository = learningRepository;
        this.auditRepository = auditRepository;
    }

    @Transactional(readOnly = true)
    public LearningPlatformStatsDto platformStats() {
        long learnedMerchantPairs = learningRepository.count();

        long correctedCount = 0;
        long resetCount = 0;
        long learnedCount = 0;
        for (Object[] row : auditRepository.platformActionCounts()) {
            MerchantLearningAudit.Action action = (MerchantLearningAudit.Action) row[0];
            long count = (Long) row[1];
            switch (action) {
                case LEARNED -> learnedCount = count;
                case CORRECTED -> correctedCount = count;
                case RESET -> resetCount = count;
                default -> { /* UNDONE/MERGED aren't part of this summary, same as the per-user Summary DTO */ }
            }
        }
        long totalConfirmations = learnedCount + correctedCount;

        return new LearningPlatformStatsDto(learnedMerchantPairs, totalConfirmations, correctedCount, resetCount, trend());
    }

    /** Same LEARNED-vs-CORRECTED, oldest-first, month-bucketed shape as
     *  AnalyticsService.learningGrowth() -- see that method's doc comment for why this isn't
     *  capped to a trailing window. Not capped here either, for the same reason at platform
     *  scale: this is a lifetime trend, not a rolling metrics window.
     *
     *  Bug fix: an earlier version of this method only emitted a point for months that actually
     *  had LEARNED/CORRECTED activity, silently skipping zero-activity months in between --
     *  unlike AnalyticsService.learningGrowth(), which walks every month from the earliest to the
     *  latest activity and fills gaps with a zero point (see that method's own start/end loop).
     *  A month-by-month line chart built on the old shape would jump straight over a quiet month
     *  instead of showing it as a real zero, silently misrepresenting the platform's actual
     *  activity gaps. Fixed to walk the same start-to-end range and fill gaps the same way. */
    private List<AnalyticsDto.LearningGrowthPoint> trend() {
        List<MerchantLearningAudit> entries = auditRepository.findAll();
        if (entries.isEmpty()) return List.of();

        Map<YearMonth, long[]> byMonth = new HashMap<>(); // [0]=learned, [1]=corrected
        for (MerchantLearningAudit entry : entries) {
            if (entry.getAction() != MerchantLearningAudit.Action.LEARNED
                    && entry.getAction() != MerchantLearningAudit.Action.CORRECTED) continue;
            YearMonth m = YearMonth.from(entry.getCreatedAt().atZone(java.time.ZoneOffset.UTC));
            long[] counts = byMonth.computeIfAbsent(m, k -> new long[2]);
            if (entry.getAction() == MerchantLearningAudit.Action.LEARNED) counts[0]++;
            else counts[1]++;
        }
        if (byMonth.isEmpty()) return List.of();

        YearMonth start = byMonth.keySet().stream().min(YearMonth::compareTo).orElseThrow();
        YearMonth end = byMonth.keySet().stream().max(YearMonth::compareTo).orElseThrow();

        List<AnalyticsDto.LearningGrowthPoint> points = new ArrayList<>();
        for (YearMonth m = start; !m.isAfter(end); m = m.plusMonths(1)) {
            long[] counts = byMonth.getOrDefault(m, new long[2]);
            points.add(new AnalyticsDto.LearningGrowthPoint(m.toString(), counts[0], counts[1]));
        }
        return points;
    }
}
