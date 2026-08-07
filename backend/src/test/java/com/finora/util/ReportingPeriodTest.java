package com.finora.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bugs 05 and 06. The shared rule for "which month is this summary about", extracted so
 * {@code DashboardService} and {@code InsightsService} cannot answer it differently again.
 */
class ReportingPeriodTest {

    private static final ZoneId ZONE = UserZone.DEFAULT;

    private static String monthsAgo(int n) {
        return YearMonth.now(ZONE).minusMonths(n).toString();
    }

    @Test
    @DisplayName("the reporting month is the newest month with data, and knows when it is not current")
    void reportsOnTheNewestMonthWithDataAndSaysSo() {
        ReportingPeriod stale = ReportingPeriod.resolve(List.of(monthsAgo(3), monthsAgo(1)), ZONE);

        assertThat(stale.month()).isEqualTo(monthsAgo(1));
        assertThat(stale.isCurrent())
                .as("BUG 05: reporting on last month is fine; claiming it is this month is not")
                .isFalse();
        assertThat(stale.calendarMonth()).isEqualTo(monthsAgo(0));
    }

    @Test
    @DisplayName("data in the current month reports as current")
    void currentMonthDataIsCurrent() {
        ReportingPeriod current = ReportingPeriod.resolve(List.of(monthsAgo(1), monthsAgo(0)), ZONE);

        assertThat(current.month()).isEqualTo(monthsAgo(0));
        assertThat(current.isCurrent()).isTrue();
    }

    @Test
    @DisplayName("BUG 05: the prior month is a CALENDAR step, not the next month that happens to have data")
    void priorMonthIsACalendarStepNotThePreviousMonthWithData() {
        // A user with a gap -- data in March and July, nothing between. The old code compared July
        // against March and labelled it "vs last month".
        ReportingPeriod gapped = ReportingPeriod.resolve(List.of("2026-03", "2026-07"), ZONE);

        assertThat(gapped.priorMonth())
                .as("June has no data, sums to zero, and yields an honest 'no comparison' rather "
                        + "than a confident comparison against a month four months back")
                .isEqualTo("2026-06");
    }

    @Test
    @DisplayName("an account with no transactions reports on the real month rather than a nonexistent one")
    void noDataReportsTheCalendarMonth() {
        ReportingPeriod empty = ReportingPeriod.resolve(List.of(), ZONE);

        assertThat(empty.month()).isNull();
        assertThat(empty.isCurrent()).isTrue();
        assertThat(empty.calendarMonth()).isEqualTo(monthsAgo(0));
        assertThat(empty.priorMonth()).isNull();
    }

    @Test
    @DisplayName("the calendar month is resolved in the USER's zone, not the server's")
    void calendarMonthFollowsTheUsersZone() {
        // Kiritimati is UTC+14 and Niue is UTC-11: 25 hours apart, so for part of every month-end
        // they are in different months. Whichever way the server's own clock happens to sit, these
        // two must not both be able to equal it.
        String farEast = ReportingPeriod.resolve(List.of(), ZoneId.of("Pacific/Kiritimati")).calendarMonth();
        String farWest = ReportingPeriod.resolve(List.of(), ZoneId.of("Pacific/Niue")).calendarMonth();

        assertThat(farEast).isEqualTo(YearMonth.now(ZoneId.of("Pacific/Kiritimati")).toString());
        assertThat(farWest).isEqualTo(YearMonth.now(ZoneId.of("Pacific/Niue")).toString());
    }
}
