package com.finora.notification.api;

import com.finora.entity.User;
import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationPreference;
import com.finora.notification.repository.NotificationPreferenceRepository;
import com.finora.repository.UserRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves whether a user wants a category on a channel, from real per-user preferences plus an
 * account-status gate.
 *
 * <h2>SECURITY is forcibly on</h2>
 *
 * <p>{@code isEnabled} returns {@code true} for {@link NotificationCategory#SECURITY} without
 * consulting the preference table OR the user's account status. Rationale: a user who has
 * silenced security alerts cannot be told their password changed, which is the one notification
 * whose absence is itself a security problem -- and that reasoning applies just as much to a
 * deactivated or suspended account as to a silenced preference: a deactivated account is exactly
 * when "your password was changed" matters most. This resolves the open question the notification
 * proposal left in section 2.3, and extends it to account status.
 *
 * <h2>FINANCIAL and MARKETING are suppressed for a stepped-away account</h2>
 *
 * <p>When the user's {@code User.status} is {@code STATUS_SUSPENDED}, {@code STATUS_DEACTIVATED},
 * or {@code STATUS_PENDING_DELETION}, FINANCIAL and MARKETING both resolve to {@code false} --
 * regardless of any stored preference row, including an explicit opt-in. Those users have stepped
 * away from the account (or been made to); budget and import notifications are unwanted noise.
 * This check short-circuits before the preference table is even consulted.
 *
 * <p>{@code STATUS_DELETED} is deliberately NOT checked here: {@code EmailNotificationProvider}
 * and {@code SmsNotificationProvider} already refuse to send to a deleted user via
 * {@code User.isDeleted()}. Duplicating that check here would just be a second place for the two
 * to drift apart.
 *
 * <p>Absent a status suppression, a stored preference row wins when present; when absent, the
 * category's own default applies -- opt-out (enabled) for FINANCIAL, opt-in (disabled) for
 * MARKETING.
 *
 * <h2>Never throws into the caller</h2>
 *
 * <p>A repository lookup failure here (an actual exception, not a normal "no row" or "no user"
 * result) is caught and resolved to the same category default a missing preference row already
 * gets: deliver for FINANCIAL, suppress for MARKETING. A resolver hiccup degrades to the
 * steady-state default rather than inventing a new posture. A user id that does not resolve to a
 * row is treated as not-stepped-away for the same reason: {@code NotificationService} only ever
 * requests notifications for a user whose own action it just recorded, so a missing row here is an
 * anomaly, not evidence of a stepped-away account.
 */
@Component
public class DatabaseNotificationPreferenceResolver implements NotificationPreferenceResolver {

    private static final Logger log =
            LoggerFactory.getLogger(DatabaseNotificationPreferenceResolver.class);

    private final NotificationPreferenceRepository repository;
    private final UserRepository userRepository;

    public DatabaseNotificationPreferenceResolver(NotificationPreferenceRepository repository,
            UserRepository userRepository) {
        this.repository = repository;
        this.userRepository = userRepository;
    }

    @Override
    public boolean isEnabled(UUID userId, NotificationCategory category,
            NotificationChannel channel) {
        if (category == NotificationCategory.SECURITY) {
            return true;
        }
        try {
            if (isSteppedAway(userId)) {
                return false;
            }
            return repository.findByUserIdAndCategoryAndChannel(userId, category, channel)
                    .map(NotificationPreference::isEnabled)
                    .orElseGet(() -> defaultFor(category));
        } catch (RuntimeException e) {
            log.error("Could not resolve notification preference for user {} category {} channel "
                    + "{}; falling back to the category default", userId, category, channel, e);
            return defaultFor(category);
        }
    }

    /**
     * True when the account has stepped away: SUSPENDED, DEACTIVATED, or PENDING_DELETION.
     * STATUS_DELETED is intentionally not checked -- see the class doc comment.
     */
    private boolean isSteppedAway(UUID userId) {
        return userRepository.findById(userId)
                .map(user -> user.isSuspended() || user.isDeactivated() || user.isPendingDeletion())
                .orElse(false);
    }

    /** MARKETING is opt-in; everything else (FINANCIAL) is opt-out. */
    private boolean defaultFor(NotificationCategory category) {
        return category != NotificationCategory.MARKETING;
    }
}
