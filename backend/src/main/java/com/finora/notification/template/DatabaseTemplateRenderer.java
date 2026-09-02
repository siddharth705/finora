package com.finora.notification.template;

import com.finora.notification.domain.NotificationChannel;
import com.finora.notification.domain.NotificationTemplate;
import com.finora.notification.domain.NotificationType;
import com.finora.notification.repository.NotificationTemplateRepository;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Renders {{placeholder}} templates from the notification_templates table.
 *
 * <p>Deliberately a plain string substitution, not a templating engine: the parameter maps are
 * small and flat, and pulling in Thymeleaf/Freemarker for this would be more machinery than the
 * problem warrants.
 *
 * <p>Replaces {@code PassThroughTemplateRenderer} (Task 2's boot-only stand-in, now deleted). Only
 * one {@link TemplateRenderer} bean may exist at a time -- see that class's former doc comment for
 * why a duplicate-bean boot failure is the intended guard rather than {@code @Primary}.
 */
@Component
public class DatabaseTemplateRenderer implements TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{(\\w+)}}");

    private final NotificationTemplateRepository repository;

    public DatabaseTemplateRenderer(NotificationTemplateRepository repository) {
        this.repository = repository;
    }

    @Override
    public RenderedMessage render(NotificationType type, NotificationChannel channel,
            Map<String, String> params) {
        NotificationTemplate template = repository.findByTypeAndChannelAndActiveTrue(type, channel)
                .orElseThrow(() -> new IllegalStateException(
                        "No active notification template for " + type + " on " + channel
                                + ". A type without a template row cannot be delivered."));
        return new RenderedMessage(substitute(template.getTitleTemplate(), params),
                substitute(template.getBodyTemplate(), params));
    }

    /**
     * An unmatched {@code {{placeholder}}} is left as-is on purpose: it is a deployment/caller bug
     * (a param the caller forgot to pass, or copy referencing a param that no longer exists), and
     * the failure mode has to be chosen between three bad options -- throw (turns a cosmetic copy
     * bug into a fully suppressed notification, which is worse for the user than imperfect
     * wording), silently drop to empty string or literal "null" (actively misleading, e.g. "Your
     * statement is ready" losing its bank name with no trace anything was wrong), or leave the
     * placeholder visible. A visible {@code {{name}}} in a title is the only one of the three that
     * (a) still reaches the user with the rest of the message intact and (b) is self-evidently a
     * bug report the moment it's seen -- in a delivered email, a support ticket, or a log -- rather
     * than a silent data-quality problem no one notices.
     *
     * <p>Single pass over the original template via {@link Matcher#appendReplacement}, not
     * repeated {@code String.replace} calls over a growing result: substituting key-by-key into
     * the same accumulating string means a value inserted for one placeholder could itself contain
     * {@code {{...}}} text that a later key's substitution would then match and rewrite --
     * order-dependent (Map iteration order is unspecified) and only needs one parameter value to
     * echo template syntax. A single pass over the untouched source template can't re-scan text it
     * just inserted, so inserted values are never treated as further template syntax.
     */
    private String substitute(String template, Map<String, String> params) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String value = params.get(matcher.group(1));
            String replacement = value != null ? value : matcher.group();
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
