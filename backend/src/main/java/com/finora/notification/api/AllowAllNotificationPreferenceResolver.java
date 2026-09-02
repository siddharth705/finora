package com.finora.notification.api;

import com.finora.notification.domain.NotificationCategory;
import com.finora.notification.domain.NotificationChannel;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * PROVISIONAL. Exists only to keep the application context bootable between Task 2 (which wires
 * {@code NotificationService} as a {@code @Service} requiring a {@link NotificationPreferenceResolver}
 * bean) and Task 8, which supplies the real, DB-backed implementation.
 *
 * <p>Permits every channel for every category, unconditionally. That is a deliberately permissive
 * default, not a security posture -- it exists so the outbox write path is exercisable end to end
 * before user-configurable preferences exist, not to express any actual product decision about
 * what a user has opted into.
 *
 * <p><b>Task 8 deletes this class</b> and replaces it with {@code DatabaseNotificationPreferenceResolver},
 * which reads the user's actual saved preferences. If both classes exist at once, Spring's context
 * will fail to start with a duplicate-bean error for {@link NotificationPreferenceResolver} rather
 * than silently picking one -- deliberate: no {@code @Primary} and no
 * {@code @ConditionalOnMissingBean} here, so a forgotten deletion of this class surfaces loudly at
 * boot instead of quietly leaving every user's real preferences unenforced.
 */
@Component
public class AllowAllNotificationPreferenceResolver implements NotificationPreferenceResolver {

    @Override
    public boolean isEnabled(UUID userId, NotificationCategory category, NotificationChannel channel) {
        return true;
    }
}
