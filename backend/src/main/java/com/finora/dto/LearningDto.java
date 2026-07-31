package com.finora.dto;

import java.time.Instant;
import java.util.UUID;

/** Financial Intelligence Workspace, Learning Engine module -- the cross-merchant view over
 *  MerchantLearningAudit that MerchantDto.AuditEntry deliberately doesn't provide (that one is
 *  scoped to a single already-known merchant, used by the Merchant Management console's
 *  per-merchant history tab; this is "every learning event across every merchant," used by the
 *  Learning Engine page's activity timeline). See LearningService's own class comment. */
public class LearningDto {

    /** Same fields as MerchantDto.AuditEntry plus merchantId/merchantName, since this spans
     *  merchants and the caller has no other way to know which merchant each row belongs to.
     *
     *  Bug fix: id was originally omitted, leaving the frontend's timeline list with no stable
     *  identifier to key its rows on -- it fell back to array index, which breaks (rows can swap
     *  data mid-render) the moment the list reorders, e.g. right after a Reset Learning call
     *  triggers a refetch that re-sorts newest-first. audit_log/AuditLogEntry already exposes its
     *  own id for exactly this reason; this record just hadn't been given the same treatment. */
    public record TimelineEntry(
            UUID id, UUID merchantId, String merchantName, String action,
            String previousCategoryName, String newCategoryName, Instant createdAt
    ) {}

    /** learnedMerchants: merchants with at least one confirmation on record (real evidence, not
     *  just a keyword-table guess). correctedCount: total CORRECTED entries ever, across every
     *  merchant -- how many times the engine's top pick has been overridden, lifetime (not just
     *  this month, unlike AnalyticsDto.LearningGrowthPoint). resetCount: how many times a user has
     *  used "Reset Learning" -- see LearningService.reset's own doc comment for why this is worth
     *  surfacing separately rather than folding into correctedCount. */
    public record Summary(int learnedMerchants, long totalConfirmations, long correctedCount, long resetCount) {}
}
