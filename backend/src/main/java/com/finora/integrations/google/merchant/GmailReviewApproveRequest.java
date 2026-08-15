package com.finora.integrations.google.merchant;

/**
 * Approve one Gmail review-queue item. {@code category} is the whole of "edit before approve"
 * (C5.4) — the only field the review table ever let a user change for a staged row (see
 * {@code ConfirmedRowIntegrity}: date/description/amount/type come from the document and cannot be
 * edited at confirm time). Null or blank means "approve with the suggested category unchanged".
 */
public record GmailReviewApproveRequest(String category) {}
