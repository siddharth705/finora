package com.finora.util;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

/**
 * Which month a summary is reporting on, and whether that is actually the month the user is
 * currently living in.
 *
 * <p><b>Reusable rule, not a point patch.</b> Two services answer this question and they answered
 * it differently, which is Bug 05. {@code InsightsService} resolved it correctly and wrote down
 * why; {@code DashboardService} — the screen that renders the answer as headline KPIs — never got
 * the same treatment and asserted "this month" over whichever month happened to be newest.
 *
 * <h2>The distinction that matters</h2>
 * There are two different months in play and conflating them is what produced Bugs 05 and 06:
 *
 * <ul>
 *   <li><b>The reporting month</b> — the newest month the user actually has data for. This is the
 *       right REPORTING period for a product built around importing statements in arrears: an
 *       empty "this month" is a worse answer than last month's real figures, which is exactly the
 *       reasoning {@code InsightsService} already records.</li>
 *   <li><b>The calendar month</b> — the month it is right now, in the user's own timezone. This is
 *       the right period for anything measured against a MONTHLY ALLOWANCE, because a monthly
 *       budget resets on a calendar boundary regardless of when the user last imported. Using the
 *       reporting month there is Bug 06: the dashboard warned "you have hit your Groceries budget"
 *       from last month's spend while the Budgets page correctly showed this month at 0%.</li>
 * </ul>
 *
 * <p><b>Reporting on the newest month with data is not the bug. Claiming it is "this month" is.</b>
 * That is why this type carries {@link #isCurrent()} rather than forcing every caller onto the
 * calendar month — a summary is allowed to report on July, it just has to say July.
 */
public record ReportingPeriod(String month, boolean isCurrent, String calendarMonth) {

    /**
     * Resolves the period from the months a user has data for.
     *
     * @param monthsWithData distinct {@code YearMonth.toString()} values, ascending. Empty is
     *                       legitimate — a brand-new account — and yields a period whose
     *                       {@link #month()} is null, which every {@code sumForMonth}-style caller
     *                       already treats as "no data" and answers with zero.
     * @param zone           the USER's zone, not the server's. A user meaningfully east or west of
     *                       the server would otherwise flip to the next month hours early or late,
     *                       which is the same defect {@code BudgetService} and
     *                       {@code NetWorthService} each already fixed for themselves.
     */
    public static ReportingPeriod resolve(List<String> monthsWithData, ZoneId zone) {
        String calendarMonth = YearMonth.now(zone).toString();
        if (monthsWithData == null || monthsWithData.isEmpty()) {
            // No data at all: report on the real month, which correctly renders as an empty
            // dashboard for a new account rather than as a period that does not exist.
            return new ReportingPeriod(null, true, calendarMonth);
        }
        String newest = monthsWithData.get(monthsWithData.size() - 1);
        return new ReportingPeriod(newest, newest.equals(calendarMonth), calendarMonth);
    }

    /**
     * The month immediately before the reporting month, on the CALENDAR — not the previous month
     * the user happens to have data for.
     *
     * <p>This is the second half of Bug 05. The prior month used to be taken as "the next entry
     * down the list of months with data", so a user with a gap — a statement for March and one for
     * July and nothing between — had July's figures compared against March's and labelled "vs last
     * month". A calendar step yields June, which has no data, sums to zero, and produces an honest
     * "no comparison available" rather than a confident comparison against a month four months
     * back.
     */
    public String priorMonth() {
        return month == null ? null : YearMonth.parse(month).minusMonths(1).toString();
    }
}
