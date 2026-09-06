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
    // Not from the original proposal §3.2 table -- added alongside AccountService's Free-tier
    // 2-account cap (the enforcement behind plans.ts's "Unlimited accounts" Plus/Premium promise,
    // previously just marketing copy with nothing checking it). Seeded in V161, same
    // Free-absent/Plus-and-Premium-enabled shape as every other key here.
    public static final String UNLIMITED_ACCOUNTS = "UNLIMITED_ACCOUNTS";
    // V162. Gmail sync is the one integration with real ongoing per-user cost (a scheduled worker
    // polling the Gmail API for as long as the connection stays live), unlike the mostly-free-CRUD
    // rest of the app -- see GmailConnectionService.beginConnect/GmailManualSyncService.syncNow/
    // GmailDiscoveryWorker.runOnce, the three places this key is actually checked.
    public static final String GMAIL_SYNC = "GMAIL_SYNC";

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
