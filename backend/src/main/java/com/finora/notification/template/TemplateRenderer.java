package com.finora.notification.template;

import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationType;
import java.util.Map;

public interface TemplateRenderer {
    /**
     * @throws IllegalStateException when no active template exists for this type/channel -- a
     *     missing template is a deployment error, not a per-send condition to swallow silently.
     */
    RenderedMessage render(NotificationType type, NotificationChannel channel,
            Map<String, String> params);
}
