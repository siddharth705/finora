package com.finora.notification.template;

import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationType;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * PROVISIONAL. Exists only to keep the application context bootable between Task 2 (which wires
 * {@code NotificationService} as a {@code @Service} requiring a {@link TemplateRenderer} bean) and
 * Task 7, which supplies the real, DB-backed implementation.
 *
 * <p>Renders without a database: the title is the notification type's own name, and the body is a
 * flat {@code key=value} join of whatever params the caller supplied. No copy review, no
 * localization, no per-channel formatting -- this is not meant to produce anything a user should
 * see, only to prove the wiring works and let earlier tasks' tests run against a real bean instead
 * of a hand-rolled test double.
 *
 * <p><b>Task 7 deletes this class</b> and replaces it with {@code DatabaseTemplateRenderer}, which
 * renders from the {@code notification_templates} table. If both classes exist at once, Spring's
 * context will fail to start with a duplicate-bean error for {@link TemplateRenderer} -- that is
 * intentional (see the class comment on {@link com.finora.notification.api
 * .AllowAllNotificationPreferenceResolver} for why a loud failure is preferred over
 * {@code @Primary}/{@code @ConditionalOnMissingBean} silently picking one).
 */
@Component
public class PassThroughTemplateRenderer implements TemplateRenderer {

    @Override
    public RenderedMessage render(NotificationType type, NotificationChannel channel,
            Map<String, String> params) {
        String body = params.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(", "));
        return new RenderedMessage(type.name(), body);
    }
}
