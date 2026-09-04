package com.finora.support;

import com.finora.dto.PagedResponse;
import com.finora.entity.FeedbackEntry;
import com.finora.repository.FeedbackEntryRepository;
import com.finora.util.PageBounds;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Orchestration for feedback: submission and the admin list. Smaller than
 * {@link SupportTicketService} because feedback has no ownership check to enforce on read — the
 * only read path is the admin list, gated entirely by the controller's class-level
 * {@code @PreAuthorize}, so unlike tickets there is no dual-audience route here and nothing for a
 * service-level ownership predicate to do.
 */
@Service
public class FeedbackService {

    private final FeedbackEntryRepository repository;
    private final ClientIdentity clientIdentity;

    public FeedbackService(FeedbackEntryRepository repository, ClientIdentity clientIdentity) {
        this.repository = repository;
        this.clientIdentity = clientIdentity;
    }

    @Transactional
    public FeedbackDto.Summary submit(UUID userId, FeedbackEntry.Type type, FeedbackEntry.Context context, String message) {
        FeedbackEntry entry = new FeedbackEntry();
        entry.setUserId(userId);
        entry.setType(type);
        entry.setContext(context);
        entry.setMessage(message.trim());
        entry.setSource(clientIdentity.platform());
        entry.setAppVersion(clientIdentity.appVersion());
        return FeedbackDto.Summary.from(repository.save(entry));
    }

    @Transactional(readOnly = true)
    public PagedResponse<FeedbackDto.Summary> adminList(FeedbackEntry.Type type, FeedbackEntry.Context context,
                                                         int page, int size) {
        Pageable pageable = PageRequest.of(PageBounds.safePage(page), PageBounds.safeSize(size));
        return PagedResponse.of(repository.findForAdmin(type, context, pageable).map(FeedbackDto.Summary::from));
    }

    /** Phase 9's counts panel — unfiltered by design, same as {@code countGrouped()}'s own doc
     *  comment scopes it: a breakdown across everything, not a filtered slice matching whatever the
     *  list view's type/context filter happens to be set to. */
    @Transactional(readOnly = true)
    public FeedbackDto.Breakdown breakdown() {
        return FeedbackDto.Breakdown.from(repository.countGrouped());
    }
}
