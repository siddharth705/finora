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
 *
 * <h2>Invariants</h2>
 * <pre>
 *   reporting month  = the newest month containing transaction data     ({@link #month()})
 *                      null only when the account has no transactions at all
 *   calendar month   = the month it is NOW, in the USER's timezone      ({@link #calendarMonth()})
 *                      never null
 *   isCurrent        = reporting month == calendar month                ({@link #isCurrent()})
 *                      true when there is no data, since there is then nothing stale to warn about
 *   prior month      = reporting month minus one CALENDAR month         ({@link #priorMonth()})
 *                      never "the next month down the list that happens to have data"
 * </pre>
 *
 * <h2>Which month a feature should use</h2>
 *
 * <p>The test is <b>what does this number mean if the user has not imported this month yet?</b>
 * If the honest answer is "nothing", it is a REPORTING figure. If the honest answer is "zero", it
 * is measured against a calendar period and must use the calendar month.
 *
 * <table border="1">
 *   <caption>Feature to month mapping</caption>
 *   <tr><th>Feature</th><th>Month</th><th>Why</th></tr>
 *   <tr><td>Dashboard KPIs — income, expense, net, savings rate</td><td>Reporting</td>
 *       <td>An empty "this month" is a worse answer than last month's real figures.</td></tr>
 *   <tr><td>Dashboard spend-by-category donut</td><td>Reporting</td>
 *       <td>Same set the KPIs describe; splitting them would be incoherent.</td></tr>
 *   <tr><td>Dashboard month-over-month deltas</td><td>Reporting, vs {@link #priorMonth()}</td>
 *       <td>Compared against the calendar-previous month so a gap in history is visible as no
 *           comparison rather than a wrong one.</td></tr>
 *   <tr><td><b>Budget-exceeded notifications</b></td><td><b>Calendar</b></td>
 *       <td>A monthly allowance resets on a calendar boundary regardless of when the user last
 *           imported. This is Bug 06, and it is the one figure on the dashboard response that does
 *           NOT follow the reporting month.</td></tr>
 *   <tr><td>{@code BudgetService.listForUser}</td><td>Calendar</td>
 *       <td>Same reason; it has always done this, which is what the dashboard now agrees with.</td></tr>
 *   <tr><td>{@code InsightsService} sentences and movers</td><td>Reporting</td>
 *       <td>Resolves its own, over an EXPENSE-only set — so it can legitimately pick a different
 *           month than the dashboard for an account whose newest month holds only income. Do not
 *           label one with the other's period.</td></tr>
 *   <tr><td>Goal / net-worth snapshot dates</td><td>Calendar</td>
 *       <td>A dated record of "now" is not a report on a period.</td></tr>
 * </table>
 *
 * <p><b>Clients must never re-derive either month.</b> A client that hardcodes "this month" over a
 * reporting figure reintroduces Bug 05 no matter what the server computed — which is why
 * {@code DashboardSummaryDto} carries {@code reportingMonth} and {@code reportingMonthIsCurrent},
 * and why {@code scripts/check-reporting-period-labels.py} fails the build if any client asserts a
 * month next to a dashboard figure instead of rendering the one it was given.
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
