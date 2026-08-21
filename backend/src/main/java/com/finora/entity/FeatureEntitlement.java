package com.finora.entity;

import jakarta.persistence.*;

import java.util.UUID;

/**
 * D-28 PR4-A. Which features a plan grants (proposal §3.2) -- the FAIL-CLOSED counterpart to
 * {@link FeatureFlag}'s fail-open platform toggle. See {@code EntitlementService.hasEntitlement}
 * for why "no row matches" must mean false here, never true. Not extending BaseEntity: a plain
 * mapping row, same reasoning as {@link FeatureFlag} itself.
 */
@Entity
@Table(name = "feature_entitlements")
public class FeatureEntitlement {

    // Product-approved feature keys (proposal §3.2's table) -- named here so callers of
    // EntitlementService.hasEntitlement never hand-type a string that could silently typo-mismatch
    // what was actually seeded in V99.
    public static final String BASIC_DASHBOARD = "BASIC_DASHBOARD";
    public static final String ADVANCED_REPORTS = "ADVANCED_REPORTS";
    public static final String EXTENDED_HISTORY = "EXTENDED_HISTORY";
    public static final String INVESTMENT_INSIGHTS = "INVESTMENT_INSIGHTS";
    public static final String FINO_AI = "FINO_AI";
    public static final String PRIORITY_SUPPORT = "PRIORITY_SUPPORT";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "plan_id", nullable = false)
    private UUID planId;

    @Column(name = "feature_key", nullable = false, length = 50)
    private String featureKey;

    @Column(nullable = false)
    private boolean enabled = true;

    public UUID getId() { return id; }
    public UUID getPlanId() { return planId; }
    public void setPlanId(UUID planId) { this.planId = planId; }
    public String getFeatureKey() { return featureKey; }
    public void setFeatureKey(String featureKey) { this.featureKey = featureKey; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
