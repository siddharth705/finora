package com.finora.service;

import com.finora.entity.Merchant;
import com.finora.entity.MerchantAlias;
import com.finora.repository.MerchantAliasRepository;
import com.finora.repository.MerchantRepository;
import com.finora.util.CategoryRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a raw transaction description to a single canonical Merchant, grouping variants
 * like "AMAZON SELLER SERVICES", "Amazon Pay", and "Amazon Marketplace" under one identity.
 *
 * Exact alias matching (same normalized description seen before) always wins first. For a
 * genuinely new description, grouping is done by the FIRST significant token of the normalized
 * text ("amazon" from all three examples above) — a deliberately simple heuristic, not fuzzy
 * matching or NLP. It correctly groups the common case (a brand name as the first word) and
 * will miss less obvious cases (e.g. a payment processor's legal name that doesn't share a
 * token with the brand it processes for) — those are exactly what the manual "merge merchants"
 * feature exists to fix by hand, rather than trying to make the heuristic itself smarter.
 *
 * <h2>The N+1 that used to be here, and why the decision to keep it was reversed (Bug 35)</h2>
 *
 * Any description whose exact spelling has not been seen before falls through to a match against
 * the user's whole merchant table. That load used to happen once per ROW — real bank descriptions
 * carry a per-transaction reference ("SWIGGY ORDER 4471"), so nearly every row is a new alias even
 * when it is the same few merchants repeatedly, and the cost scaled with rows × the user's
 * lifetime merchant count. Measured in {@link MerchantNormalizationEngineTest}: a 500-row
 * statement with 50 distinct merchants performed <b>500 full merchant-table loads</b>.
 *
 * <p>This class previously recorded four candidate fixes, all rejected, and the reasoning is worth
 * keeping because three of those rejections still stand:
 *
 * <ol>
 *   <li><b>Snapshot the merchant list once per import.</b> Still wrong, and the trap the
 *       implementation below has to avoid. {@code resolve()} CREATES merchants as it goes, so row
 *       3 would not see the merchant row 1 created and would make its own — three "Swiggy" rows
 *       instead of one, splitting the user's spend and splitting what the learning engine is
 *       taught. That is a silent data-quality bug, far worse than the latency it saves. Guarded by
 *       {@code differentSpellingsOfANewMerchantCollapseOntoOne} and
 *       {@code aMerchantCreatedMidTransactionIsSeenByLaterRows}.</li>
 *
 *   <li><b>Transaction-scoped cache.</b> <b>This is now what the code does</b> — see
 *       {@link #merchantsByFirstToken}. It was rejected on two grounds and both have since failed:
 *       <ul>
 *         <li>"changes the signature every caller uses" — it does not have to. The memo lives
 *             inside this class, keyed on the transaction, so no caller signature changed and the
 *             dry-run-flag objection that rules out threading a context object does not apply.</li>
 *         <li>"to fix a sub-second cost" — that premise was wrong. End-to-end measurement found
 *             realistic statement sizes taking <i>minutes</i>, with permitted 10 MB uploads never
 *             completing at all. The cost was never sub-second on a real account; it was
 *             sub-second on a test fixture with almost no merchant history.</li>
 *       </ul>
 *       500 loads became 1, with no behavioural change: same rows, same comparison, same first
 *       winner.</li>
 *
 *   <li><b>Persist the normalised first token as an indexed column.</b> Still the most principled
 *       fix and still not done, for the reason recorded before: the token is computed by
 *       {@code CategoryRules.normalize} + {@code firstSignificantToken} in Java, so the column
 *       needs a backfill reproducing that logic in SQL exactly, plus a standing obligation to
 *       recompute it wherever a canonical name changes (admin rename and merge). A backfill that
 *       is subtly different silently stops matching merchants that used to match. Revisit if one
 *       load per import per user ever becomes the bottleneck; it is not today.</li>
 *
 *   <li><b>Read a two-column projection.</b> Still rejected. Built and measured once, then
 *       reverted: it leaves the query count unchanged and <i>adds</i> a {@code findById} per token
 *       match — measured at +450 lookups on a 500-row import. Moot now that the count is 1.</li>
 * </ol>
 *
 * What was fixed, because it was an outright waste rather than a trade-off:
 * {@code ImportService} called {@code findByUserId(userId).size()} twice per import purely to
 * report how many merchants were newly learned, hydrating the entire table to produce a number the
 * database returns directly. That is now {@code countByUserId}.
 */
@Service
public class MerchantNormalizationEngine {

    private static final Logger log = LoggerFactory.getLogger(MerchantNormalizationEngine.class);

    private final MerchantRepository merchantRepository;
    private final MerchantAliasRepository merchantAliasRepository;

    public MerchantNormalizationEngine(MerchantRepository merchantRepository, MerchantAliasRepository merchantAliasRepository) {
        this.merchantRepository = merchantRepository;
        this.merchantAliasRepository = merchantAliasRepository;
    }

    /**
     * The widest value {@code merchant_aliases.normalized_alias} and {@code merchants.canonical_name}
     * can hold (V7). Both are {@code VARCHAR(255)}, and both are written from parser output.
     */
    private static final int MAX_STORED_LENGTH = 255;

    /** Transaction-scoped key for the per-user merchant memo -- see merchantsByFirstToken. */
    private static final String MEMO_KEY = MerchantNormalizationEngine.class.getName() + ".merchantMemo";

    @Transactional
    public Merchant resolve(UUID userId, String description) {
        String rawAlias = CategoryRules.normalize(description);

        // Cut it to size before the database refuses it. A real credit-card statement produced a
        // 400-character "merchant" -- one narration that had absorbed a page of cheque
        // instructions -- and the VARCHAR(255) insert aborted the JDBC batch. That marks the whole
        // transaction rollback-only, so the import died later with UnexpectedRollbackException: an
        // HTTP 500, for a document the parser had merely misread.
        //
        // Catching it downstream cannot help, which is the crux. By the time the constraint fires
        // the transaction is already poisoned, and no handling un-poisons it. The write simply
        // must not be attempted.
        //
        // Truncating rather than skipping, because every caller dereferences this result -- a null
        // would trade a rollback for an NPE, the same 500 by another route. And truncation loses
        // less than it looks: the boilerplate is APPENDED, so the first 255 characters still carry
        // the real narration. It also stays deterministic, so the same misparsed description maps
        // to the same merchant on re-import instead of multiplying rows.
        if (rawAlias.length() > MAX_STORED_LENGTH) {
            log.warn("Truncating a {}-character description to {} for merchant resolution. This is "
                    + "a PARSER fault, not a data fault: a narration that long means row "
                    + "segmentation absorbed surrounding page text.",
                    rawAlias.length(), MAX_STORED_LENGTH);
        }
        // A separate final, not a reassignment: normalizedAlias is captured by the lambda below,
        // and reassigning it makes it no longer effectively final.
        final String normalizedAlias = fitToColumn(rawAlias);

        var existingAlias = merchantAliasRepository.findByUserIdAndNormalizedAlias(userId, normalizedAlias);
        if (existingAlias.isPresent()) {
            // findByIdAndUserId, not a bare findById. This is safe either way -- the id comes from
            // an alias row already scoped to this user -- but MerchantRepository's own comment
            // states the rule as "never a bare findById", and a rule with one silent exception is
            // a rule the next reader cannot trust. Same query cost, no exception to explain.
            return merchantRepository.findByIdAndUserId(existingAlias.get().getMerchantId(), userId)
                    .orElseGet(() -> createMerchantAndAlias(userId, description, normalizedAlias));
        }

        String firstToken = firstSignificantToken(normalizedAlias);
        if (firstToken != null) {
            var candidate = merchantsByFirstToken(userId).get(firstToken);
            if (candidate != null) {
                addAlias(candidate.getId(), userId, normalizedAlias);
                return candidate;
            }
        }

        Merchant created = createMerchantAndAlias(userId, description, normalizedAlias);
        // Keep the memo consistent with what this transaction has now written. Without this, two
        // rows in the same statement naming the same new merchant would each miss the memo, each
        // create a merchant, and the second would collide on the alias index -- turning the fix
        // into a new source of the exact failure addAlias() exists to survive.
        rememberInMemo(userId, created);
        return created;
    }

    /**
     * The user's merchants indexed by first significant token, loaded at most once per transaction.
     *
     * <p><b>Bug 35.</b> This lookup used to be
     * {@code merchantRepository.findByUserId(userId).stream().filter(...)} — a full load of every
     * merchant the user owns, executed inside a per-ROW call. {@code resolve} runs once per staged
     * row and this branch is taken on every alias miss, which is the common case on a first import
     * rather than an edge case. A 300-row statement therefore performed up to 300 full merchant
     * loads, and the cost grew with the user's history rather than with the size of the import.
     * The end-to-end symptom was measured independently: realistic statement sizes taking minutes,
     * and permitted 10 MB uploads never completing.
     *
     * <p>Memoized per transaction rather than indexed by a stored column. An indexed lookup would
     * be faster still, but the token is computed by {@code CategoryRules.normalize} +
     * {@link #firstSignificantToken} in Java, so a column would need a backfill that reproduces
     * that logic in SQL exactly — and a backfill that is subtly different silently stops matching
     * merchants that used to match. Memoizing changes no behaviour at all: the same rows, the same
     * comparison, the same first winner. It only stops asking the database the identical question
     * once per row.
     *
     * <p>Scoped to the transaction, not to the bean, deliberately. A longer-lived cache would go
     * stale against other users' writes and would need invalidation this class has no way to
     * observe; a transaction is exactly the window in which the answer cannot change underneath us
     * except by our own writes, which {@link #rememberInMemo} accounts for. Outside a transaction
     * the memo is skipped entirely and the old behaviour stands, so nothing depends on callers
     * being transactional.
     *
     * <p>First match wins, matching the previous {@code findFirst()} on an unordered query. The
     * memo therefore keeps the FIRST merchant seen for a token rather than the last.
     */
    private Map<String, Merchant> merchantsByFirstToken(UUID userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return indexByFirstToken(merchantRepository.findByUserId(userId));
        }

        @SuppressWarnings("unchecked")
        Map<UUID, Map<String, Merchant>> perUser =
                (Map<UUID, Map<String, Merchant>>) TransactionSynchronizationManager.getResource(MEMO_KEY);
        if (perUser == null) {
            perUser = new HashMap<>();
            TransactionSynchronizationManager.bindResource(MEMO_KEY, perUser);
            // Unbind when the transaction ends, or the resource leaks onto whatever this thread
            // serves next -- which for a request thread is another user's request.
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(MEMO_KEY);
                }
            });
        }
        return perUser.computeIfAbsent(userId,
                id -> indexByFirstToken(merchantRepository.findByUserId(id)));
    }

    private Map<String, Merchant> indexByFirstToken(List<Merchant> merchants) {
        Map<String, Merchant> byToken = new HashMap<>();
        for (Merchant merchant : merchants) {
            String token = firstSignificantToken(CategoryRules.normalize(merchant.getCanonicalName()));
            // putIfAbsent: first match wins, which is what findFirst() did.
            if (token != null) byToken.putIfAbsent(token, merchant);
        }
        return byToken;
    }

    private void rememberInMemo(UUID userId, Merchant created) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        @SuppressWarnings("unchecked")
        Map<UUID, Map<String, Merchant>> perUser =
                (Map<UUID, Map<String, Merchant>>) TransactionSynchronizationManager.getResource(MEMO_KEY);
        if (perUser == null) return;
        Map<String, Merchant> byToken = perUser.get(userId);
        if (byToken == null) return;
        String token = firstSignificantToken(CategoryRules.normalize(created.getCanonicalName()));
        if (token != null) byToken.putIfAbsent(token, created);
    }

    private Merchant createMerchantAndAlias(UUID userId, String description, String normalizedAlias) {
        Merchant merchant = new Merchant();
        merchant.setUserId(userId);
        // merchants.canonical_name is VARCHAR(255) too, and is derived from the same description,
        // so it carries the identical hazard. Guarding only the alias would have moved the
        // rollback one insert further down rather than removing it.
        merchant.setCanonicalName(fitToColumn(toDisplayName(CategoryRules.extractMerchant(description))));
        merchant = merchantRepository.save(merchant);
        addAlias(merchant.getId(), userId, normalizedAlias);
        return merchant;
    }

    /**
     * Bug fix: this was check-then-act against {@code UNIQUE(user_id, normalized_alias)}, with
     * nothing between the check and the insert. Two concurrent imports for the same user hitting
     * the same new description both saw "no alias yet" and both inserted — and
     * {@code ImportConcurrencyLimiter}'s semaphore does not prevent it, being a global permit
     * count with no per-user scoping, so two imports for one user is an ordinary occurrence rather
     * than an exotic race (one person confirming two statements from two tabs).
     *
     * <p>The consequence was disproportionate: the losing insert threw
     * {@code DataIntegrityViolationException} from inside the confirm transaction, so the user's
     * ENTIRE import rolled back over a duplicate alias row — a row whose only purpose is caching a
     * name match, and which by definition already existed with the right value.
     *
     * <p>{@code saveAndFlush} forces the constraint check to happen HERE, where it can be caught,
     * rather than at commit — the same reasoning {@code BootstrapService} records for its own
     * race. Losing is not a failure: the other writer stored exactly what this one wanted to, so
     * the correct response is to carry on.
     */
    /** Never let parser output exceed what the column can hold; see resolve()'s own reasoning. */
    private static String fitToColumn(String value) {
        if (value == null || value.length() <= MAX_STORED_LENGTH) return value;
        return value.substring(0, MAX_STORED_LENGTH);
    }

    private void addAlias(UUID merchantId, UUID userId, String normalizedAlias) {
        if (merchantAliasRepository.findByUserIdAndNormalizedAlias(userId, normalizedAlias).isPresent()) return;
        MerchantAlias alias = new MerchantAlias();
        alias.setMerchantId(merchantId);
        alias.setUserId(userId);
        alias.setNormalizedAlias(normalizedAlias);
        try {
            merchantAliasRepository.saveAndFlush(alias);
        } catch (DataIntegrityViolationException e) {
            // Only a genuine lost race is benign, and this catch used to assume every integrity
            // violation was one. It was not: a 400-character alias tripped the VARCHAR(255) limit
            // here and was logged, at DEBUG, as "created concurrently" -- a diagnosis that was
            // simply untrue, for an error that had already poisoned the transaction. The import
            // then died somewhere unrelated with UnexpectedRollbackException, and the log line
            // that could have explained it said the opposite.
            //
            // Re-reading is what tells the two apart. If the row is now present, another writer
            // really did win and there is nothing to do. If it is absent, this was not a race, and
            // saying so is worth more than a reassuring message.
            boolean lostARace = merchantAliasRepository
                    .findByUserIdAndNormalizedAlias(userId, normalizedAlias).isPresent();
            if (!lostARace) {
                log.error("Alias insert for user {} failed for a reason that is NOT a concurrent "
                        + "insert ({} chars). The transaction is likely already rollback-only, so "
                        + "the import will fail downstream -- this line is the real cause.",
                        userId, normalizedAlias.length(), e);
                throw e;
            }
            log.debug("Alias '{}' for user {} was created concurrently; keeping the existing row.",
                    normalizedAlias, userId);
        }
    }

    private String firstSignificantToken(String normalized) {
        if (normalized == null || normalized.isBlank()) return null;
        String[] tokens = normalized.split(" ");
        for (String t : tokens) {
            if (t.length() > 2) return t;
        }
        return tokens.length > 0 ? tokens[0] : null;
    }

    private String toDisplayName(String extractedMerchant) {
        if (extractedMerchant == null || extractedMerchant.isBlank()) return "Unknown Merchant";
        String[] words = extractedMerchant.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }
}
