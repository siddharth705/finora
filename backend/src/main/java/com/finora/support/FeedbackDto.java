package com.finora.support;

import com.finora.entity.ClientPlatform;
import com.finora.entity.FeedbackEntry;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.UUID;

/** The feedback API contract. Deliberately smaller than {@link SupportTicketDto} — feedback has
 *  one shape, not a list/detail split, because there is no per-row admin action to build a detail
 *  view around (§3.3 of the proposal). */
public final class FeedbackDto {

    private FeedbackDto() {}

    public record CreateRequest(
            @NotNull(message = "type is required") FeedbackEntry.Type type,
            @NotNull(message = "context is required") FeedbackEntry.Context context,
            @NotBlank(message = "message is required") String message
    ) {}

    public record Summary(
            UUID id,
            UUID userId,
            FeedbackEntry.Type type,
            FeedbackEntry.Context context,
            ClientPlatform source,
            String message,
            Instant createdAt
    ) {
        public static Summary from(FeedbackEntry entry) {
            return new Summary(entry.getId(), entry.getUserId(), entry.getType(), entry.getContext(),
                    entry.getSource(), entry.getMessage(), entry.getCreatedAt());
        }
    }
}
