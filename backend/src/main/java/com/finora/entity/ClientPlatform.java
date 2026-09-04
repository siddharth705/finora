package com.finora.entity;

/**
 * Which client an inbound request came from.
 *
 * <p>One enum shared by {@link SupportTicket} and {@link FeedbackEntry} rather than an identical
 * {@code Source} nested in each. Two copies of the same three constants would drift the first time
 * one of them gained a value, and the whole point of the column is that counts across tickets and
 * feedback are comparable.
 *
 * <p>Deliberately about the <b>client</b>, not the feature. {@link FeedbackEntry.Context} answers
 * "which part of the product" and this answers "which app" — keeping them separate is what makes
 * "are mobile users hitting more import problems than web users" answerable without
 * cross-referencing user-agent strings after the fact.
 *
 * <p>Persisted as its name via {@code @Enumerated(EnumType.STRING)}, with no database CHECK
 * constraint, following the rest of this schema.
 */
public enum ClientPlatform {
    WEB,
    MOBILE_ANDROID,
    MOBILE_IOS
}
