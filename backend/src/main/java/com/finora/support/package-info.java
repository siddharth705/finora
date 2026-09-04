/**
 * The support feature module: in-product help requests and product feedback.
 *
 * <p>Reference minting ({@link com.finora.support.SupportTicketIdGenerator}), the client-identity
 * resolver ({@link com.finora.support.ClientIdentity}), and — as of Phase 3/4 — the full HTTP
 * surface and orchestration: {@link com.finora.support.SupportTicketController} and
 * {@link com.finora.support.AdminSupportTicketController} for tickets, {@link
 * com.finora.support.FeedbackController} and {@link com.finora.support.AdminFeedbackController} for
 * feedback, backed by {@link com.finora.support.SupportTicketService} and
 * {@link com.finora.support.FeedbackService}. {@link com.finora.support.SupportAttachmentUpload} is
 * the attachment-validation counterpart to {@code com.finora.imports.StatementUpload}.
 *
 * <p>{@code SupportTicket}, {@code SupportTicketAttachment}, {@code SupportTicketInternalNote} and
 * {@code FeedbackEntry} stay in {@code com.finora.entity}, and their repositories in
 * {@code com.finora.repository}, following {@code com.finora.budgets} — a feature module here
 * holds the controller, service and DTO only. The account-lifecycle and data-export services both
 * read these repositories directly, which is the same reason budgets are split that way.
 */
package com.finora.support;
