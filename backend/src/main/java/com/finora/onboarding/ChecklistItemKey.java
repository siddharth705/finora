package com.finora.onboarding;

/** The 6 getting-started checklist items (spec §3), in display order. The 4 DERIVED items have no
 *  stored state -- OnboardingService computes them live from existing tables. The 2 EXPLICIT items
 *  have no other signal in the schema and are recorded in user_checklist_events; only these two
 *  may ever be POSTed as complete (OnboardingService.completeChecklistItem rejects the other 4). */
public enum ChecklistItemKey {
    COMPLETE_PROFILE(false),
    IMPORT_STATEMENT(false),
    REVIEW_TRANSACTIONS(true),
    CREATE_BUDGET(false),
    CREATE_GOAL(false),
    VIEW_INSIGHTS(true);

    private final boolean explicit;

    ChecklistItemKey(boolean explicit) {
        this.explicit = explicit;
    }

    public boolean isExplicit() {
        return explicit;
    }
}
