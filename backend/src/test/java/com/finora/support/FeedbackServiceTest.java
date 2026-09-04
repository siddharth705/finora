package com.finora.support;

import com.finora.dto.PagedResponse;
import com.finora.entity.ClientPlatform;
import com.finora.entity.FeedbackEntry;
import com.finora.repository.FeedbackEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mockito, matching {@code SupportTicketServiceTest}'s own precedent -- no ownership branching or
 * status matrix here to need a real Postgres for; {@code countGrouped()}'s own aggregation query is
 * covered against a real database separately, by {@code SupportRepositoryIT}.
 */
class FeedbackServiceTest {

    private FeedbackEntryRepository repository;
    private ClientIdentity clientIdentity;
    private FeedbackService service;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repository = mock(FeedbackEntryRepository.class);
        clientIdentity = mock(ClientIdentity.class);
        service = new FeedbackService(repository, clientIdentity);

        when(repository.save(any(FeedbackEntry.class))).thenAnswer(inv -> inv.getArgument(0));
        when(clientIdentity.platform()).thenReturn(ClientPlatform.MOBILE_ANDROID);
        when(clientIdentity.appVersion()).thenReturn("1.0.0");
    }

    @Test
    void submit_setsSourceAndAppVersionFromClientIdentity_notFromTheCaller() {
        FeedbackDto.Summary result = service.submit(userId, FeedbackEntry.Type.BUG, FeedbackEntry.Context.IMPORT_FLOW, "  broken  ");

        assertThat(result.userId()).isEqualTo(userId);
        assertThat(result.type()).isEqualTo(FeedbackEntry.Type.BUG);
        assertThat(result.context()).isEqualTo(FeedbackEntry.Context.IMPORT_FLOW);
        assertThat(result.source()).isEqualTo(ClientPlatform.MOBILE_ANDROID);
        // Trimmed, same as SupportTicketService's own field handling.
        assertThat(result.message()).isEqualTo("broken");
    }

    @Test
    void adminList_delegatesToTheFilteredRepositoryQuery() {
        Page<FeedbackEntry> page = new PageImpl<>(List.of());
        when(repository.findForAdmin(eq(FeedbackEntry.Type.BUG), eq(FeedbackEntry.Context.HELP), any())).thenReturn(page);

        PagedResponse<FeedbackDto.Summary> result = service.adminList(FeedbackEntry.Type.BUG, FeedbackEntry.Context.HELP, 0, 25);

        assertThat(result.content()).isEmpty();
        verify(repository).findForAdmin(eq(FeedbackEntry.Type.BUG), eq(FeedbackEntry.Context.HELP), any());
    }

    private FeedbackEntryRepository.FeedbackBreakdown row(
            FeedbackEntry.Type type, FeedbackEntry.Context context, ClientPlatform source, long total) {
        FeedbackEntryRepository.FeedbackBreakdown row = mock(FeedbackEntryRepository.FeedbackBreakdown.class);
        when(row.getType()).thenReturn(type);
        when(row.getContext()).thenReturn(context);
        when(row.getSource()).thenReturn(source);
        when(row.getTotal()).thenReturn(total);
        return row;
    }

    @Test
    void breakdown_foldsTheFlatGroupingIntoThreeDimensionTalliesAndAGrandTotal() {
        // Extracted to locals first, not built inline inside List.of() -- nesting mock() calls
        // inside another when(...).thenReturn(...)'s argument evaluation interleaves Mockito's
        // stubbing state and throws UnfinishedStubbingException (see row()'s own when() calls).
        FeedbackEntryRepository.FeedbackBreakdown row1 = row(FeedbackEntry.Type.BUG, FeedbackEntry.Context.IMPORT_FLOW, ClientPlatform.WEB, 5);
        FeedbackEntryRepository.FeedbackBreakdown row2 = row(FeedbackEntry.Type.BUG, FeedbackEntry.Context.HELP, ClientPlatform.WEB, 2);
        FeedbackEntryRepository.FeedbackBreakdown row3 = row(FeedbackEntry.Type.FEATURE_REQUEST, FeedbackEntry.Context.IMPORT_FLOW, ClientPlatform.MOBILE_ANDROID, 3);
        when(repository.countGrouped()).thenReturn(List.of(row1, row2, row3));

        FeedbackDto.Breakdown result = service.breakdown();

        assertThat(result.total()).isEqualTo(10);
        // Highest count first within each dimension.
        assertThat(result.byType()).extracting("label", "total")
                .containsExactly(tuple("BUG", 7L), tuple("FEATURE_REQUEST", 3L));
        assertThat(result.byContext()).extracting("label", "total")
                .containsExactly(tuple("IMPORT_FLOW", 8L), tuple("HELP", 2L));
        assertThat(result.bySource()).extracting("label", "total")
                .containsExactly(tuple("WEB", 7L), tuple("MOBILE_ANDROID", 3L));
    }

    @Test
    void breakdown_tiedCounts_breakAlphabeticallyByLabel_deterministicRegardlessOfHashOrder() {
        // groupingBy() collects into a HashMap -- with no tie-break, two equal counts could swap
        // order between requests on hash-bucket layout alone, nothing about the underlying data
        // having changed. Repeated 20 times: a flaky, order-dependent tie-break would eventually
        // produce FEATURE_REQUEST-before-BUG and fail this, where a single run could pass by luck.
        for (int i = 0; i < 20; i++) {
            FeedbackEntryRepository.FeedbackBreakdown row1 = row(FeedbackEntry.Type.BUG, FeedbackEntry.Context.HELP, ClientPlatform.WEB, 3);
            FeedbackEntryRepository.FeedbackBreakdown row2 = row(FeedbackEntry.Type.FEATURE_REQUEST, FeedbackEntry.Context.HELP, ClientPlatform.WEB, 3);
            when(repository.countGrouped()).thenReturn(List.of(row1, row2));

            FeedbackDto.Breakdown result = service.breakdown();

            assertThat(result.byType()).extracting("label")
                    .as("iteration %d", i)
                    .containsExactly("BUG", "FEATURE_REQUEST");
        }
    }

    @Test
    void breakdown_onNoFeedbackAtAll_returnsZeroTotalAndEmptyTallies() {
        when(repository.countGrouped()).thenReturn(List.of());

        FeedbackDto.Breakdown result = service.breakdown();

        assertThat(result.total()).isZero();
        assertThat(result.byType()).isEmpty();
        assertThat(result.byContext()).isEmpty();
        assertThat(result.bySource()).isEmpty();
    }
}
