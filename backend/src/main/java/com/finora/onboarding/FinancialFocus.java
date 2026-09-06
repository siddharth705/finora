package com.finora.onboarding;

/** The 7 Financial Focus chip options (spec §3, Screen 2) -- a closed set, never free text.
 *  Display copy (the emoji + label shown in the UI) lives entirely in the frontend; this name is
 *  a stable backend identifier only, so wording can change without a migration. */
public enum FinancialFocus {
    TRACK_SPENDING,
    MANAGE_BUDGETS,
    SAVE_FOR_GOAL,
    SEE_ALL_ACCOUNTS,
    IMPROVE_HABITS,
    REDUCE_DEBT,
    EXPLORING
}
