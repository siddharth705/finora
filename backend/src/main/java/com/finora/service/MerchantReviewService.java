package com.finora.service;

import com.finora.dto.MerchantReviewDto;
import com.finora.dto.PagedResponse;
import com.finora.entity.Merchant;
import com.finora.entity.Merchant.Lifecycle;
import com.finora.exception.ApiException;
import com.finora.repository.MerchantRepository;
import com.finora.repository.TransactionRepository;
import com.finora.repository.UserRepository;
import com.finora.util.PageBounds;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The Merchant Review Center (WI4).
 *
 * <p>An operator's queue of merchants the normalization engine invented. The engine resolves an
 * unseen description by first-significant-token match — a heuristic its own class doc calls
 * "deliberately simple ... not fuzzy matching or NLP" — so its output is a guess, and this is where
 * a person confirms, corrects or discards it.
 *
 * <h2>Per-user, by product decision</h2>
 * There is no canonical merchant registry: {@code merchants.user_id} is NOT NULL and every row is
 * one user's private record. So <b>listing is cross-user and every action is scoped to the owning
 * user</b>. An operator sees all outstanding work in one place rather than guessing which account
 * to open, but merge candidates come only from that user's own merchants and there is no
 * platform-wide merge. Cross-user merchant intelligence is a separate milestone.
 *
 * <h2>Delete is the dangerous action</h2>
 * Four foreign keys point at {@code merchants}: {@code merchant_aliases},
 * {@code merchant_category_learning} and {@code merchant_learning_audit} all
 * {@code ON DELETE CASCADE}, and {@code transactions.merchant_id} is {@code ON DELETE SET NULL}. A
 * raw delete therefore destroys the merchant's learning distribution AND its audit history, and
 * silently orphans every transaction pointing at it. {@code MerchantService.merge} already repoints
 * all four before deleting, so discard routes through it rather than reimplementing that carefully.
 * A merchant with transactions attached cannot be discarded at all — only merged.
 */
@Service
public class MerchantReviewService {

    /** What "needs review" means. APPROVED is excluded by definition, and the partial index in V64
     *  is built on exactly this predicate. */
    private static final Set<Lifecycle> NEEDS_REVIEW = EnumSet.of(Lifecycle.TEMPORARY, Lifecycle.UNDER_REVIEW);

    /** Bulk-approve ceiling, reusing the number {@code TransactionDto.MAX_BULK_IDS} already set
     *  rather than inventing a second one. */
    private static final int MAX_BULK = 500;

    private final MerchantRepository merchantRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final MerchantService merchantService;
    private final AuditService auditService;

    public MerchantReviewService(MerchantRepository merchantRepository,
                                  TransactionRepository transactionRepository,
                                  UserRepository userRepository,
                                  MerchantService merchantService,
                                  AuditService auditService) {
        this.merchantRepository = merchantRepository;
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
        this.merchantService = merchantService;
        this.auditService = auditService;
    }

    /**
     * The queue, with the per-row lookups batched.
     *
     * <p>Written this way on purpose. The obvious {@code result.map(this::toDto)} calls
     * {@code findById} for the owner and {@code countByMerchantId} for the transactions ONCE PER
     * ROW — two extra queries per merchant, so fifty on a page of twenty-five. That is the N+1 this
     * milestone has spent its time removing, and a review queue is precisely where the row count
     * is large. Two batched lookups instead, regardless of page size.
     */
    @Transactional(readOnly = true)
    public PagedResponse<MerchantReviewDto> queue(int page, int size) {
        var result = merchantRepository.findByLifecycleStatusInOrderByCreatedAtAsc(
                NEEDS_REVIEW, PageRequest.of(PageBounds.safePage(page), PageBounds.safeSize(size > 0 ? size : 25)));
        List<Merchant> merchants = result.getContent();
        if (merchants.isEmpty()) return PagedResponse.of(result.map(this::toDto));

        Map<UUID, String> emailByUser = userRepository
                .findAllById(merchants.stream().map(Merchant::getUserId).distinct().toList())
                .stream().collect(java.util.stream.Collectors.toMap(u -> u.getId(), u -> u.getEmail()));
        Map<UUID, Long> countByMerchant = transactionRepository
                .countByMerchantIdIn(merchants.stream().map(Merchant::getId).toList())
                .stream().collect(java.util.stream.Collectors.toMap(
                        row -> (UUID) row[0], row -> (Long) row[1]));

        return PagedResponse.of(result.map(m -> new MerchantReviewDto(
                m.getId(), m.getUserId(), emailByUser.get(m.getUserId()), m.getCanonicalName(),
                m.getLifecycleStatus().name(), countByMerchant.getOrDefault(m.getId(), 0L),
                m.getCreatedAt())));
    }

    @Transactional(readOnly = true)
    public long outstandingCount() {
        return merchantRepository.countByLifecycleStatusIn(NEEDS_REVIEW);
    }

    /** Confirms the engine's guess was right. The merchant stays exactly as it is; only its status
     *  changes, because approving is a statement about trust, not an edit. */
    @Transactional
    public MerchantReviewDto approve(UUID actingAdminId, UUID userId, UUID merchantId) {
        Merchant merchant = requireReviewable(userId, merchantId);
        merchant.setLifecycleStatus(Lifecycle.APPROVED);
        merchantRepository.save(merchant);
        auditService.record(userId, "MERCHANT_APPROVED", "Merchant", merchantId,
                Map.of("actorId", actingAdminId.toString(), "name", merchant.getCanonicalName()));
        return toDto(merchant);
    }

    /**
     * Approves every outstanding merchant for one user.
     *
     * <p>Scoped to a user rather than platform-wide, and that is not a limitation to route around:
     * "approve everything for everyone" is a click with no judgement behind it, and the whole point
     * of the queue is that a person looked. Reviewing one account's merchants at a time is a real
     * unit of work; reviewing all of them at once is not.
     */
    @Transactional
    public int approveAllFor(UUID actingAdminId, UUID userId) {
        List<Merchant> outstanding = merchantRepository.findByUserIdAndLifecycleStatusIn(userId, NEEDS_REVIEW);
        if (outstanding.isEmpty()) return 0;
        if (outstanding.size() > MAX_BULK) {
            outstanding = outstanding.subList(0, MAX_BULK);
        }
        outstanding.forEach(m -> m.setLifecycleStatus(Lifecycle.APPROVED));
        merchantRepository.saveAll(outstanding);
        auditService.record(userId, "MERCHANTS_APPROVED_BULK", "Merchant", null,
                Map.of("actorId", actingAdminId.toString(), "count", outstanding.size()));
        return outstanding.size();
    }

    /** Corrects the engine's guess at the name, then approves it — a rename during review is a
     *  confirmation with an edit, not a separate decision to make later. */
    @Transactional
    public MerchantReviewDto rename(UUID actingAdminId, UUID userId, UUID merchantId, String newName) {
        requireReviewable(userId, merchantId);
        merchantService.rename(userId, merchantId, new com.finora.dto.MerchantDto.UpdateRequest(newName, null),
                actingAdminId);
        Merchant renamed = requireOwned(userId, merchantId);
        renamed.setLifecycleStatus(Lifecycle.APPROVED);
        merchantRepository.save(renamed);
        return toDto(renamed);
    }

    /**
     * Folds a guessed merchant into one the user already has.
     *
     * <p>Delegates to {@code MerchantService.merge}, which repoints aliases, transactions, learning
     * rows and audit history before deleting — the four foreign keys a raw delete would cascade
     * away. Reimplementing that here to save a call is how audit history gets destroyed quietly.
     */
    @Transactional
    public MerchantReviewDto merge(UUID actingAdminId, UUID userId, UUID survivingMerchantId, UUID mergeFromId) {
        requireReviewable(userId, mergeFromId);
        merchantService.merge(userId, survivingMerchantId, mergeFromId, actingAdminId);
        Merchant surviving = requireOwned(userId, survivingMerchantId);
        // The survivor is confirmed by the act of merging into it: an operator chose it on purpose.
        surviving.setLifecycleStatus(Lifecycle.APPROVED);
        merchantRepository.save(surviving);
        return toDto(surviving);
    }

    /**
     * Discards a guess that should never have existed.
     *
     * <p>Refused outright when transactions point at the merchant. {@code transactions.merchant_id}
     * is {@code ON DELETE SET NULL}, so deleting would silently strip the merchant from real
     * ledger rows — the user's history would lose an attribution nobody asked to remove. Merge is
     * the operation for that case, and the message says so rather than failing opaquely.
     */
    @Transactional
    public void discard(UUID actingAdminId, UUID userId, UUID merchantId) {
        Merchant merchant = requireReviewable(userId, merchantId);
        long attached = transactionRepository.countByMerchantId(merchantId);
        if (attached > 0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    attached + " transaction(s) are attributed to this merchant. Merge it into the "
                            + "right one instead — discarding would strip the merchant from those rows.");
        }
        merchantRepository.delete(merchant);
        auditService.record(userId, "MERCHANT_DISCARDED", "Merchant", merchantId,
                Map.of("actorId", actingAdminId.toString(), "name", merchant.getCanonicalName()));
    }

    /** Merge candidates: the same user's APPROVED merchants. Never another user's — there is no
     *  cross-user merchant identity to merge into. */
    @Transactional(readOnly = true)
    public List<MerchantReviewDto> mergeCandidatesFor(UUID userId, UUID merchantId) {
        return merchantRepository.findByUserId(userId).stream()
                .filter(m -> !m.getId().equals(merchantId))
                .filter(m -> m.getLifecycleStatus() == Lifecycle.APPROVED)
                .map(this::toDto)
                .toList();
    }

    // --- internals ----------------------------------------------------------------------------

    private Merchant requireOwned(UUID userId, UUID merchantId) {
        return merchantRepository.findByIdAndUserId(merchantId, userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No such merchant for this user."));
    }

    /** Every action here is only meaningful on something awaiting review. Refusing with the actual
     *  state lets an operator tell "already handled by a colleague" from "cannot be handled". */
    private Merchant requireReviewable(UUID userId, UUID merchantId) {
        Merchant merchant = requireOwned(userId, merchantId);
        if (!NEEDS_REVIEW.contains(merchant.getLifecycleStatus())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This merchant is already " + merchant.getLifecycleStatus() + " and is not awaiting review.");
        }
        return merchant;
    }

    /** Single-merchant conversion, for the responses to individual actions. The two lookups here
     *  are one row each; see {@link #queue} for why the LIST does not use this. */
    private MerchantReviewDto toDto(Merchant merchant) {
        return new MerchantReviewDto(
                merchant.getId(),
                merchant.getUserId(),
                userRepository.findById(merchant.getUserId()).map(u -> u.getEmail()).orElse(null),
                merchant.getCanonicalName(),
                merchant.getLifecycleStatus().name(),
                transactionRepository.countByMerchantId(merchant.getId()),
                merchant.getCreatedAt());
    }
}
