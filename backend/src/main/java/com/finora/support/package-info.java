/**
 * The support feature module: in-product help requests and product feedback.
 *
 * <p>Reference minting lives here ({@link com.finora.support.SupportTicketIdGenerator}); the HTTP
 * surface, orchestration and API contract land in this package as later phases add them.
 *
 * <p>{@code SupportTicket}, {@code SupportTicketAttachment}, {@code SupportTicketInternalNote} and
 * {@code FeedbackEntry} stay in {@code com.finora.entity}, and their repositories in
 * {@code com.finora.repository}, following {@code com.finora.budgets} — a feature module here
 * holds the controller, service and DTO only. The account-lifecycle and data-export services both
 * read these repositories directly, which is the same reason budgets are split that way.
 */
package com.finora.support;
