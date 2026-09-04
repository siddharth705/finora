package com.finora.support;

import com.finora.entity.ClientPlatform;
import com.finora.entity.FeedbackEntry;
import com.finora.repository.FeedbackEntryRepository;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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

    /**
     * The admin breakdown panel — proposal §3.4/Phase 9's "Counts... by Type, Context, Source",
     * nothing more (explicitly not trend detection or clustering, per
     * {@link FeedbackEntryRepository#countGrouped()}'s own doc comment, which this reuses rather
     * than issuing three separate {@code GROUP BY} queries).
     */
    public record Breakdown(long total, List<Count> byType, List<Count> byContext, List<Count> bySource) {
        public record Count(String label, long total) {}

        /** Folds the repository's one flat (type, context, source, total) grouping into three
         *  dimension-specific tallies in memory — cheap, since {@code countGrouped()} already
         *  collapsed the table down to its distinct combinations rather than one row per entry. */
        public static Breakdown from(List<FeedbackEntryRepository.FeedbackBreakdown> rows) {
            long total = rows.stream().mapToLong(FeedbackEntryRepository.FeedbackBreakdown::getTotal).sum();
            return new Breakdown(
                    total,
                    tally(rows, r -> r.getType().name()),
                    tally(rows, r -> r.getContext().name()),
                    tally(rows, r -> r.getSource().name())
            );
        }

        private static List<Count> tally(
                List<FeedbackEntryRepository.FeedbackBreakdown> rows,
                java.util.function.Function<FeedbackEntryRepository.FeedbackBreakdown, String> label
        ) {
            Map<String, Long> byLabel = rows.stream().collect(Collectors.groupingBy(
                    label, Collectors.summingLong(FeedbackEntryRepository.FeedbackBreakdown::getTotal)));
            return byLabel.entrySet().stream()
                    .map(e -> new Count(e.getKey(), e.getValue()))
                    // Highest count first -- this is a "what's most common" panel, not an alphabetical
                    // list. Tie-broken alphabetically by label, not left to fall out of groupingBy's
                    // HashMap iteration order: without a second key, two labels tied on count could
                    // swap places between one request and the next with nothing about the data having
                    // changed, which reads as the panel being unreliable rather than as a real tie.
                    .sorted(Comparator.comparingLong(Count::total).reversed().thenComparing(Count::label))
                    .toList();
        }
    }
}
