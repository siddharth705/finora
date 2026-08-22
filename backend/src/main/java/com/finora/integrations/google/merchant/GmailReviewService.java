package com.finora.integrations.google.merchant;

import com.finora.accounts.AccountDto;
import com.finora.accounts.AccountService;
import com.finora.dto.ImportDto.ConfirmRequest;
import com.finora.dto.ImportDto.ConfirmedRow;
import com.finora.dto.ImportDto.StagedRow;
import com.finora.entity.Account;
import com.finora.entity.ImportSession;
import com.finora.exception.ApiException;
import com.finora.imports.ImportService;
import com.finora.imports.ImportSessionService;
import com.finora.entity.Transaction;
import com.finora.integrations.google.GmailProcessedMessage;
import com.finora.integrations.google.GmailProcessedMessageRepository;
import com.finora.repository.AccountRepository;
import com.finora.repository.ImportSessionRepository;
import com.finora.service.CategorizationService;
import com.finora.util.BankRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Gmail-specific review surface — C5.4, D-15. A deliberately thin layer: every session this
 * reads or writes is exactly the {@link ImportSession} row {@link GmailStagingBridge} already
 * created, and approving/rejecting one goes through {@link ImportService#confirmSession} /
 * {@link ImportSessionService#deleteSession} unchanged. What this class adds is entirely
 * presentation and one piece of account bookkeeping:
 *
 * <ul>
 *   <li>{@link #listPending} unpacks each session's single {@code StagedRow} into a flat, receipt-
 *       shaped row instead of the generic "unfinished imports" list CSV/PDF sessions share.</li>
 *   <li>{@link #approve} resolves the one shared "Gmail receipts" account (find-or-create) instead
 *       of asking the user to pick or name an account on every single receipt — see
 *       {@link #resolveGmailReceiptsAccount}.</li>
 * </ul>
 *
 * <h2>Why per-receipt approve/reject is still "session-level" underneath</h2>
 *
 * {@code GmailStagingBridge} stages one receipt as one single-row session (C5-B). A true per-
 * receipt queue and a session-level confirm/discard are therefore the same operation for Gmail
 * specifically — D-15 chose the queue UX (bulk fragmentation was the real problem, not the
 * underlying data shape), not a new multi-row-per-session model. Nothing here changes that shape;
 * it only presents it differently and skips the account-picker step CSV/PDF confirm still needs.
 */
@Service
public class GmailReviewService {

    /** Matches {@code GmailStagingBridge.unknownAccount()}'s own {@code suggestedName} exactly —
     *  this is the name every Gmail receipt has suggested since C5-B, reused here as the lookup
     *  key for "does the shared account already exist". */
    static final String GMAIL_RECEIPTS_ACCOUNT_NAME = "Gmail receipts";

    /** Cosmetic domain -> display name. Deliberately not derived from {@code MerchantTemplate} or
     *  each hand-written parser's own constants: {@code ParsedReceipt} carries only the domain
     *  (see its own record), and querying the template table plus hardcoding the hand-written
     *  parsers' names for a label nothing downstream reads programmatically would be more moving
     *  parts than this list, not fewer. A domain missing from this map is not an error -- it falls
     *  back to the domain itself, exactly as honest as showing nothing extra.
     *
     *  <p>{@code Map.ofEntries}, not {@code Map.of} -- V103's 50-merchant readiness seed pushed
     *  this past {@code Map.of}'s 10-pair overload ceiling. The 50 new entries are display labels
     *  only; they say nothing about whether that merchant's template is enabled or has ever
     *  actually parsed anything (V103's own comment: every one of those 50 templates is seeded
     *  disabled, unverified against a real sample). */
    private static final Map<String, String> DISPLAY_NAMES = Map.ofEntries(
            Map.entry("amazon.in", "Amazon"),
            Map.entry("olacabs.com", "Ola"),
            Map.entry("uber.com", "Uber"),
            Map.entry("zomato.com", "Zomato"),
            Map.entry("myntra.com", "Myntra"),
            Map.entry("booking.com", "Booking.com"),
            // V103 readiness seed -- see that migration's own comment for why these 50 domains
            // are trusted but their templates are not yet enabled.
            Map.entry("swiggy.com", "Swiggy"),
            Map.entry("blinkit.com", "Blinkit"),
            Map.entry("zeptonow.com", "Zepto"),
            Map.entry("bigbasket.com", "BigBasket"),
            Map.entry("flipkart.com", "Flipkart"),
            Map.entry("meesho.com", "Meesho"),
            Map.entry("ajio.com", "Ajio"),
            Map.entry("nykaa.com", "Nykaa"),
            Map.entry("tatacliq.com", "Tata Cliq"),
            Map.entry("snapdeal.com", "Snapdeal"),
            Map.entry("firstcry.com", "FirstCry"),
            Map.entry("lenskart.com", "Lenskart"),
            Map.entry("decathlon.in", "Decathlon"),
            Map.entry("croma.com", "Croma"),
            Map.entry("reliancedigital.in", "Reliance Digital"),
            Map.entry("dominos.co.in", "Domino's"),
            Map.entry("pizzahut.co.in", "Pizza Hut"),
            Map.entry("eatsure.com", "EatSure"),
            Map.entry("licious.in", "Licious"),
            Map.entry("countrydelight.in", "Country Delight"),
            Map.entry("irctc.co.in", "IRCTC"),
            Map.entry("makemytrip.com", "MakeMyTrip"),
            Map.entry("yatra.com", "Yatra"),
            Map.entry("goibibo.com", "Goibibo"),
            Map.entry("cleartrip.com", "Cleartrip"),
            Map.entry("easemytrip.com", "EaseMyTrip"),
            Map.entry("redbus.in", "RedBus"),
            Map.entry("bookmyshow.com", "BookMyShow"),
            Map.entry("pvrcinemas.com", "PVR Cinemas"),
            Map.entry("inoxmovies.com", "INOX"),
            Map.entry("phonepe.com", "PhonePe"),
            Map.entry("paytm.com", "Paytm"),
            Map.entry("cred.club", "CRED"),
            Map.entry("rapido.bike", "Rapido"),
            Map.entry("urbancompany.com", "Urban Company"),
            Map.entry("dunzo.com", "Dunzo"),
            Map.entry("porter.in", "Porter"),
            Map.entry("1mg.com", "1mg"),
            Map.entry("pharmeasy.in", "PharmEasy"),
            Map.entry("netmeds.com", "Netmeds"),
            Map.entry("netflix.com", "Netflix"),
            Map.entry("hotstar.com", "Disney+ Hotstar"),
            Map.entry("sonyliv.com", "SonyLIV"),
            Map.entry("spotify.com", "Spotify"),
            Map.entry("airtel.in", "Airtel"),
            Map.entry("jio.com", "Jio"),
            Map.entry("myvi.in", "Vi"),
            Map.entry("tatapower.com", "Tata Power"),
            Map.entry("policybazaar.com", "Policybazaar"),
            Map.entry("hdfcergo.com", "HDFC ERGO"));

    private final ImportSessionRepository importSessionRepository;
    private final ImportSessionService importSessionService;
    private final ImportService importService;
    private final AccountRepository accountRepository;
    private final AccountService accountService;
    private final GmailProcessedMessageRepository processedMessages;

    public GmailReviewService(ImportSessionRepository importSessionRepository,
                               ImportSessionService importSessionService,
                               ImportService importService,
                               AccountRepository accountRepository,
                               AccountService accountService,
                               GmailProcessedMessageRepository processedMessages) {
        this.importSessionRepository = importSessionRepository;
        this.importSessionService = importSessionService;
        this.importService = importService;
        this.accountRepository = accountRepository;
        this.accountService = accountService;
        this.processedMessages = processedMessages;
    }

    /** "Transactions found" on the connection status panel (C5.4) -- every message this mailbox
     *  has ever produced a receipt from, regardless of review state. Lives here rather than on
     *  {@code GoogleOAuthController} directly: a controller reaching into a repository bypasses
     *  the service layer CODING_STANDARDS.md requires (LayerDependencyDirectionTest), and this is
     *  the same "how much Gmail review work exists" question {@link #listPending} already answers
     *  for the STAGED half of it. */
    public int countTransactionsFound(UUID connectionId) {
        return (int) processedMessages.countByConnectionIdAndOutcome(
                connectionId, GmailProcessedMessage.Outcome.PARSED);
    }

    /** "Needs review" on the connection status panel (C5.4) -- how many staged Gmail sessions
     *  {@link #listPending} would currently return, without materialising the rows themselves. */
    public int countNeedsReview(UUID userId) {
        return (int) importSessionRepository.countByUserIdAndSourceAndStatus(
                userId, ImportSession.SOURCE_GMAIL, ImportSession.STATUS_STAGED);
    }

    public List<GmailReviewItemDto> listPending(UUID userId) {
        return importSessionRepository
                .findByUserIdAndSourceAndStatusOrderByCreatedAtDesc(
                        userId, ImportSession.SOURCE_GMAIL, ImportSession.STATUS_STAGED)
                .stream()
                .map(this::toItem)
                .toList();
    }

    private GmailReviewItemDto toItem(ImportSession session) {
        StagedRow row = onlyStagedRow(session);
        String domain = row.description();
        return new GmailReviewItemDto(
                session.getId(),
                displayNameFor(domain),
                domain,
                row.amount(),
                row.date(),
                row.suggestedCategory(),
                row.confidence(),
                session.getCreatedAt(),
                reasoningFor(domain, row));
    }

    static String displayNameFor(String domain) {
        return DISPLAY_NAMES.getOrDefault(domain, domain);
    }

    /**
     * The review queue's "why" — C6.1. Two real facts, stated plainly, never dressed up as more
     * than they are:
     *
     * <ul>
     *   <li>Amount and date came from a domain the trusted-sender gate (C3) already authenticated
     *       before this row could exist at all — worth saying, since the queue itself carries no
     *       other trace of that check having happened.</li>
     *   <li>Category: {@code GmailStagingBridge} stages every receipt with {@code
     *       categorySource = "default"} today — there is no merchant-to-category engine yet
     *       (C6.3, deliberately deferred by D-17). Saying so here, rather than presenting "Other"
     *       as if it were a real detection, is the same honesty {@code ParsedReceipt}'s own class
     *       doc insists on for confidence: a field that could be mistaken for more than it is
     *       needs to say what it actually is.</li>
     * </ul>
     *
     * <p>Routes through {@link CategorizationService#decisionSourceFor} rather than checking
     * {@code "default".equals(row.categorySource())} directly — that's the one canonical mapping
     * {@code ImportService} also uses at confirm time to set {@code Transaction.decisionSource},
     * including its fallback of any unrecognized value to {@code MERCHANT_DEFAULT}. Matching it
     * here means this pre-confirm reasoning and {@code TransactionExplanationService}'s post-
     * confirm explanation always agree about whether a category was actually detected, instead of
     * two independent guesses that could drift apart. The English wording of the caveat itself is
     * still independently maintained in both places (the two moments call for different phrasing —
     * "check it below" only makes sense pre-approval) — if this fact ever changes (C6.3 ships
     * detection), also update {@code TransactionExplanationService#defaultExplanation}'s
     * GMAIL_IMPORT branch, which states the same fact for an already-confirmed transaction.
     */
    static String reasoningFor(String domain, StagedRow row) {
        String base = "Amount and date read from a verified " + displayNameFor(domain) + " email.";
        if (CategorizationService.decisionSourceFor(row.categorySource()) == Transaction.DecisionSource.MERCHANT_DEFAULT) {
            return base + " Category isn't auto-detected yet, so it defaults to \"Other\" — check it below.";
        }
        return base;
    }

    /**
     * Confirms the session into the shared "Gmail receipts" account -- see the class doc for why
     * that account, not a per-approval picker. {@code categoryOverride} is the entire "edit"
     * capability; blank/null keeps the row's own suggested category, matching how the CSV/PDF
     * review table already treats an untouched category select.
     */
    public void approve(UUID userId, UUID sessionId, String categoryOverride) {
        ImportSession session = requireGmailSession(userId, sessionId);
        StagedRow row = onlyStagedRow(session);
        String category = (categoryOverride == null || categoryOverride.isBlank())
                ? row.suggestedCategory() : categoryOverride;

        ConfirmedRow confirmedRow = new ConfirmedRow(
                row.date(), row.description(), row.amount(), row.type(),
                category, true,
                row.categorySource(), row.ruleId(), row.likelyDuplicate(),
                row.referenceNumber(), row.balanceAfter(), true);

        UUID accountId = resolveGmailReceiptsAccount(userId);
        ConfirmRequest request = new ConfirmRequest(
                sessionId, List.of(confirmedRow), accountId, null, null, null, null);
        importService.confirmSession(userId, request);
    }

    public void reject(UUID userId, UUID sessionId) {
        requireGmailSession(userId, sessionId);
        importSessionService.deleteSession(userId, sessionId);
    }

    /**
     * Find-or-create, keyed on {@link #GMAIL_RECEIPTS_ACCOUNT_NAME}. Without this, every approval
     * would either need a picker (the exact per-session friction D-15 chose the queue UX to avoid)
     * or -- if a naive caller always passed a fresh {@code NewAccountRequest} -- would create a new
     * same-named account on every single approval, since none of these synthetic accounts carry a
     * {@code productIdentityHash} for {@code ImportService}'s own dedup-on-confirm to key off.
     *
     * <p>Accepted, narrow race: find-then-create is not atomic, and {@code accounts} carries no
     * unique constraint on {@code (user_id, name)}. Two approvals racing on a user's very first
     * ever Gmail approval could each see "not found" and each create a "Gmail receipts" account --
     * a cosmetic duplicate the user can rename or merge, not a correctness or data-loss issue. A
     * unique index plus retry-on-conflict would close it; not built for C5.4, since the window only
     * exists once per user (every approval after the first hits the existing-account branch) and
     * D-15 scoped this phase to the minimum that makes C5 usable, not to closing every theoretical
     * race a v1 could accept.
     */
    private UUID resolveGmailReceiptsAccount(UUID userId) {
        return accountRepository.findFirstByUserIdAndName(userId, GMAIL_RECEIPTS_ACCOUNT_NAME)
                .map(Account::getId)
                .orElseGet(() -> accountService.create(userId, new AccountDto.CreateRequest(
                        GMAIL_RECEIPTS_ACCOUNT_NAME, Account.Type.SAVINGS.name(), BigDecimal.ZERO,
                        null, null, null, null, null, BankRegistry.UNKNOWN_ID, null, null),
                        userId).id());
    }

    private StagedRow onlyStagedRow(ImportSession session) {
        List<StagedRow> rows = importSessionService.readStagedRows(session);
        if (rows.size() != 1) {
            // GmailStagingBridge stages exactly one row per receipt -- always. More or fewer means
            // this session was never created by that path, which the requireGmailSession source
            // check above should already have caught; this is belt-and-braces against a future
            // caller reaching here some other way.
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Expected exactly one staged row for a Gmail-sourced session.");
        }
        return rows.get(0);
    }

    private ImportSession requireGmailSession(UUID userId, UUID sessionId) {
        ImportSession session = importSessionService.getOwnedSession(userId, sessionId);
        if (!ImportSession.SOURCE_GMAIL.equals(session.getSource())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "This isn't a Gmail-sourced import session.");
        }
        return session;
    }
}
