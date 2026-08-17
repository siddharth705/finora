package com.finora.dto;

import java.time.Instant;
import java.util.List;

/**
 * D-25 PR3-B. "Your Financial Journey" -- five milestones in the fixed order a new user actually
 * moves through, event-based (a real timestamp, no fixed Day-N framing -- see the PR3 proposal).
 * Built by FinancialJourneyService from timestamps that already exist on User/StatementImport/
 * Budget/Goal, plus Goal.completedAt (added alongside this DTO for the fifth milestone).
 */
public record FinancialJourneyDto(List<Milestone> milestones) {

    public static final String ACCOUNT_CREATED = "ACCOUNT_CREATED";
    public static final String FIRST_IMPORT = "FIRST_IMPORT";
    public static final String FIRST_BUDGET = "FIRST_BUDGET";
    public static final String FIRST_GOAL = "FIRST_GOAL";
    public static final String FIRST_GOAL_ACHIEVED = "FIRST_GOAL_ACHIEVED";

    public record Milestone(String type, boolean completed, Instant completedAt) {}
}
