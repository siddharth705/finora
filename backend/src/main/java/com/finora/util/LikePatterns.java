package com.finora.util;

/**
 * Makes a user's search text mean itself inside a SQL {@code LIKE}.
 *
 * <p>Every search query in this application binds the term as a parameter and wraps it in
 * {@code LIKE LOWER(CONCAT('%', :q, '%'))}. Parameter binding makes that safe from injection --
 * this is emphatically NOT an injection fix -- but it does nothing about {@code %} and {@code _},
 * which {@code LIKE} interprets as wildcards wherever they appear, including inside the bound
 * value. So an admin searching for the literal text {@code 50%} matched every row containing
 * {@code 50} followed by anything, and {@code a_b} matched {@code axb}. Silently wrong result
 * sets, on admin user search, transaction search, the audit Activity Feed, bank search and
 * global merchant search -- wrong in the direction that returns MORE than was asked for, which is
 * why nobody noticed.
 *
 * <p>{@code %} is not exotic in this domain. Financial descriptions carry it ("2.5% CASHBACK",
 * "INT @ 6.75%"), and it is exactly the kind of thing someone pastes into a search box.
 *
 * <p><b>Relies on PostgreSQL's default escape character.</b> The SQL standard says {@code LIKE}
 * has no escape character unless an {@code ESCAPE} clause supplies one; PostgreSQL departs from
 * that and defaults to backslash, which is what lets this be a caller-side fix that needs no
 * change to any query. This application is PostgreSQL-only (see {@code docker-compose.yml} and
 * every migration in {@code db/migration}), so that is a fact about the database rather than an
 * assumption -- but a port to another engine has to add {@code ESCAPE '\'} to each query, and
 * this note is where that is written down.
 */
public final class LikePatterns {

    private LikePatterns() {}

    /**
     * Escapes {@code \}, {@code %} and {@code _} so each matches itself literally.
     *
     * <p>The backslash must be escaped FIRST, or the backslashes introduced for {@code %} and
     * {@code _} would themselves be escaped a second time and end up matching a literal
     * backslash.
     *
     * @param term raw user input; null and blank pass through untouched, since callers already
     *             treat those as "no filter" and must keep doing so
     */
    public static String escape(String term) {
        if (term == null || term.isEmpty()) return term;
        return term.replace("\\", "\\\\")
                   .replace("%", "\\%")
                   .replace("_", "\\_");
    }
}
