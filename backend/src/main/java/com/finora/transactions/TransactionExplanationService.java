package com.finora.transactions;

import com.finora.entity.Category;
import com.finora.entity.CategoryRule;
import com.finora.entity.Merchant;
import com.finora.entity.Transaction;
import com.finora.repository.CategoryRepository;
import com.finora.repository.CategoryRuleRepository;
import com.finora.repository.MerchantRepository;
import com.finora.repository.TransactionRepository;
import com.finora.security.OwnershipGuard;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * "Why this category?" — a small, deliberately separate service rather than a new dependency on
 * {@link TransactionService} (already 10 collaborators), the same reasoning {@code
 * GmailReviewService}'s own class doc gives for staying out of {@code ImportService}: this is a
 * thin, presentation-only read over data another pipeline already wrote, not a new decision.
 *
 * <p>Every category decision this codebase makes already records which of seven paths it came
 * from ({@link Transaction.DecisionSource}, set by {@code CategorizationService.suggest}) and,
 * where relevant, which {@link CategoryRule} matched. Nothing here computes a new answer — it
 * reads the one already on the row and renders it in English.
 */
@Service
public class TransactionExplanationService {

    private final TransactionRepository transactionRepository;
    private final CategoryRuleRepository categoryRuleRepository;
    private final MerchantRepository merchantRepository;
    private final CategoryRepository categoryRepository;

    public TransactionExplanationService(TransactionRepository transactionRepository,
                                          CategoryRuleRepository categoryRuleRepository,
                                          MerchantRepository merchantRepository,
                                          CategoryRepository categoryRepository) {
        this.transactionRepository = transactionRepository;
        this.categoryRuleRepository = categoryRuleRepository;
        this.merchantRepository = merchantRepository;
        this.categoryRepository = categoryRepository;
    }

    public TransactionExplanationDto explain(UUID userId, UUID transactionId) {
        Transaction t = OwnershipGuard.requireOwned(
                transactionRepository.findById(transactionId), Transaction::getUserId, userId, "Transaction");
        Integer confidence = t.getDecisionConfidence();
        TransactionExplanationDto.ReconciliationExplanationDto reconciliation = reconciliationExplanationFor(t);

        return switch (t.getDecisionSource()) {
            case MANUAL -> new TransactionExplanationDto(
                    "MANUAL", "You set this category yourself.", List.of(), confidence, reconciliation);
            case USER_RULE -> ruleExplanation(t, "USER_RULE",
                    "Matched a rule you created.", confidence, reconciliation);
            case GLOBAL_RULE -> ruleExplanation(t, "GLOBAL_RULE",
                    "Matched one of Finora's built-in rules.", confidence, reconciliation);
            case LEARNED_PATTERN -> new TransactionExplanationDto(
                    "LEARNED_PATTERN",
                    "Categorized based on how you've categorized " + merchantPhrase(t) + " before.",
                    List.of("Every time you confirm or correct a category, Finora remembers it for that merchant."),
                    confidence, reconciliation);
            case KEYWORD_MATCH -> new TransactionExplanationDto(
                    "KEYWORD_MATCH",
                    "Matched a keyword Finora recognizes in the description.",
                    List.of(), confidence, reconciliation);
            case STRUCTURAL_P2P -> new TransactionExplanationDto(
                    "STRUCTURAL_P2P",
                    // Direction-neutral on purpose: the detector matches narration shape and
                    // never inspects txn_type, so roughly a quarter of these rows are money
                    // RECEIVED. "payment to a person" was wrong for those. The panel states only
                    // what was established -- a person on the other side -- and leaves direction to
                    // the amount already on the row. See CategorizationService.P2P_CATEGORY.
                    "Recognized as a transfer involving a person, from the wording of the description.",
                    // Deliberately NOT "no merchant was involved": every transaction carries a
                    // merchantId (ImportService sets it unconditionally, and suggest() returns
                    // one), so the ledger shows a merchant on this very row -- a panel denying it
                    // would contradict the screen it sits on. What is actually true is that no
                    // rule, learned pattern, or keyword matched, and the description's SHAPE was
                    // the remaining signal.
                    List.of("No rule, learned pattern, or keyword matched — the description's "
                            + "structure was the signal.",
                            "If this isn't right, correcting it teaches Finora for next time."),
                    confidence, reconciliation);
            case FILE_PROVIDED -> new TransactionExplanationDto(
                    "FILE_PROVIDED",
                    "The imported file specified this category directly.",
                    List.of(), confidence, reconciliation);
            case MERCHANT_DEFAULT -> defaultExplanation(t, confidence, reconciliation);
        };
    }

    private TransactionExplanationDto ruleExplanation(Transaction t, String source, String fallbackSummary,
                                                        Integer confidence,
                                                        TransactionExplanationDto.ReconciliationExplanationDto reconciliation) {
        CategoryRule rule = t.getDecisionRuleId() == null
                ? null : categoryRuleRepository.findById(t.getDecisionRuleId()).orElse(null);
        // A rule can be edited or deleted after it matched -- the transaction it already
        // categorized keeps its decisionSource/decisionRuleId regardless, so this stays the
        // honest answer ("a rule matched, here's what's known about it now") rather than an
        // error when the id no longer resolves to today's rule set.
        if (rule == null) {
            return new TransactionExplanationDto(source, fallbackSummary,
                    List.of("The specific rule is no longer available (it may have been edited or removed since)."),
                    confidence, reconciliation);
        }
        String condition = fieldLabel(rule.getField()) + " " + operatorLabel(rule.getOperator())
                + " " + comparisonValueLabel(rule);
        String summary = fallbackSummary + " " + condition + " → " + rule.getActionValue() + ".";
        return new TransactionExplanationDto(source, summary,
                List.of("Rule condition: " + condition, "Assigns category: " + rule.getActionValue()),
                confidence, reconciliation);
    }

    // Same fact as GmailReviewService.reasoningFor's "isn't auto-detected yet" caveat (independently
    // worded on purpose -- this is the after-the-fact ledger explanation, not the pre-approval
    // review queue). If C6.3 ships category detection, update both.
    private TransactionExplanationDto defaultExplanation(Transaction t, Integer confidence,
                                                           TransactionExplanationDto.ReconciliationExplanationDto reconciliation) {
        String categoryName = t.getCategoryId() == null ? "this category"
                : categoryRepository.findById(t.getCategoryId()).map(Category::getName).orElse("this category");
        if (t.getSource() == Transaction.Source.GMAIL_IMPORT) {
            return new TransactionExplanationDto("MERCHANT_DEFAULT",
                    "Imported from a Gmail receipt (" + merchantPhrase(t)
                            + "). Finora doesn't auto-detect a category for this merchant yet, so it defaulted to \""
                            + categoryName + "\".",
                    List.of("No rule, learned pattern, or keyword matched this transaction."),
                    confidence, reconciliation);
        }
        return new TransactionExplanationDto("MERCHANT_DEFAULT",
                "No rule, learned pattern, or keyword matched, so this defaulted to \"" + categoryName + "\".",
                List.of(), confidence, reconciliation);
    }

    /**
     * "Why this match?" -- null for the common case ({@code OK}, nothing matched this row).
     * Reads {@code reconciliationExplanation}'s {@code reason} map straight through into bullet
     * lines rather than re-deriving anything; the matched counterpart id comes from the entity's
     * own status-specific column, not re-parsed out of that JSON, so it can never disagree with it.
     */
    private TransactionExplanationDto.ReconciliationExplanationDto reconciliationExplanationFor(Transaction t) {
        Transaction.ReconciliationStatus status = t.getReconciliationStatus();
        if (status == Transaction.ReconciliationStatus.OK) return null;

        UUID matchedId = switch (status) {
            case DUPLICATE -> t.getIsDuplicateOf();
            case TRANSFER -> t.getTransferPairId();
            case REFUND, REVERSAL -> t.getRefundOfTransactionId();
            // No counterpart transaction -- see ReconciliationExplanation.investmentTransfer's own
            // comment on why this classification has nothing to point at.
            case INVESTMENT_TRANSFER -> null;
            // No counterpart transaction either -- a superseded statement was replaced, not
            // matched against another row.
            case SUPERSEDED -> null;
            case OK -> null; // unreachable, guarded above
        };

        java.util.Map<String, Object> reason = reasonMap(t);
        String summary = reconciliationSummary(status, reason);
        List<String> evidence = reason.isEmpty() ? List.of() : reconciliationEvidence(status, reason);
        return new TransactionExplanationDto.ReconciliationExplanationDto(
                status.name(), matchedId, summary, evidence);
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> reasonMap(Transaction t) {
        java.util.Map<String, Object> envelope = t.getReconciliationExplanation();
        Object reason = envelope == null ? null : envelope.get("reason");
        return reason instanceof java.util.Map<?, ?> m ? (java.util.Map<String, Object>) m : java.util.Map.of();
    }

    private String reconciliationSummary(Transaction.ReconciliationStatus status, java.util.Map<String, Object> reason) {
        return switch (status) {
            case DUPLICATE -> "Matched as a duplicate of an existing transaction — same account, date, "
                    + "amount, and description.";
            case TRANSFER -> {
                Object days = reason.get("dateDifferenceDays");
                yield "Matched as a transfer between your own accounts"
                        + (days != null ? ", " + days + " day(s) apart" : "") + ".";
            }
            case REFUND -> {
                boolean sameMerchant = Boolean.TRUE.equals(reason.get("sameMerchant"));
                boolean keyword = Boolean.TRUE.equals(reason.get("refundKeyword"));
                yield "Matched as a refund of an earlier purchase" + reasonClause(keyword, sameMerchant) + ".";
            }
            case REVERSAL -> {
                boolean sameMerchant = Boolean.TRUE.equals(reason.get("sameMerchant"));
                yield "Matched as a reversal of an earlier purchase, based on the wording of this "
                        + "transaction's description" + (sameMerchant ? " and a matching merchant" : "") + ".";
            }
            case INVESTMENT_TRANSFER -> "Excluded from spend as an investment transfer — matched the "
                    + "\"Investments\" category, not counted as spending.";
            case SUPERSEDED -> "Excluded from spend — the statement this transaction came from was "
                    + "replaced by a later re-upload of the same period.";
            case OK -> ""; // unreachable, guarded by the caller
        };
    }

    private String reasonClause(boolean keyword, boolean sameMerchant) {
        if (keyword && sameMerchant) return ", based on both its wording and a matching merchant";
        if (keyword) return ", based on its wording";
        if (sameMerchant) return ", based on a matching merchant";
        return "";
    }

    private List<String> reconciliationEvidence(Transaction.ReconciliationStatus status, java.util.Map<String, Object> reason) {
        return switch (status) {
            case TRANSFER -> List.of(
                    "Opposite direction: " + reason.getOrDefault("oppositeDirection", "?"),
                    "Amount difference: ₹" + reason.getOrDefault("amountDifference", "0"),
                    "Days apart: " + reason.getOrDefault("dateDifferenceDays", "?")
                            + " (window: " + reason.getOrDefault("dayWindowApplied", "?") + ")");
            case REFUND -> List.of(
                    "Refund amount: ₹" + reason.getOrDefault("refundAmount", "?"),
                    "Original purchase: ₹" + reason.getOrDefault("purchaseAmount", "?"),
                    Boolean.TRUE.equals(reason.get("partialRefund")) ? "This is a partial refund" : "Full refund");
            case REVERSAL -> List.of(
                    "Reversal amount: ₹" + reason.getOrDefault("reversalAmount", "?"),
                    "Original purchase: ₹" + reason.getOrDefault("purchaseAmount", "?"),
                    Boolean.TRUE.equals(reason.get("partialReversal")) ? "This is a partial reversal" : "Full reversal");
            case INVESTMENT_TRANSFER -> List.of(
                    "Amount: ₹" + reason.getOrDefault("amount", "?"),
                    "Category: " + reason.getOrDefault("category", "Investments"));
            case DUPLICATE, OK, SUPERSEDED -> List.of();
        };
    }

    private String merchantPhrase(Transaction t) {
        if (t.getMerchantId() != null) {
            String name = merchantRepository.findById(t.getMerchantId())
                    .map(Merchant::getCanonicalName).orElse(null);
            if (name != null && !name.isBlank()) {
                return "\"" + name + "\"";
            }
        }
        return t.getMerchant() != null && !t.getMerchant().isBlank() ? "\"" + t.getMerchant() + "\"" : "this merchant";
    }

    private static String fieldLabel(CategoryRule.Field field) {
        return switch (field) {
            case DESCRIPTION -> "description";
            case MERCHANT -> "merchant";
            case AMOUNT -> "amount";
            case ACCOUNT_TYPE -> "account type";
        };
    }

    private static String operatorLabel(CategoryRule.Operator operator) {
        return switch (operator) {
            case CONTAINS -> "contains";
            case EQUALS -> "equals";
            case STARTS_WITH -> "starts with";
            case GT -> "is greater than";
            case LT -> "is less than";
            case BETWEEN -> "is between";
        };
    }

    /**
     * {@code CategoryRule.comparisonValue} is opaque storage, not display text — for every
     * operator except BETWEEN it's already the one value the user typed, but {@code
     * RuleEngineService.matchesBetween}'s own doc comment says BETWEEN packs two numbers as
     * {@code "low,high"} (e.g. {@code "1000,5000"}). Quoting that literally would read as
     * {@code amount is between "1000,5000"} — the storage encoding leaking into a sentence this
     * class's own doc comment promises is plain English.
     */
    private static String comparisonValueLabel(CategoryRule rule) {
        if (rule.getOperator() != CategoryRule.Operator.BETWEEN) {
            return "\"" + rule.getComparisonValue() + "\"";
        }
        String[] parts = rule.getComparisonValue().split(",", 2);
        return parts.length == 2 ? parts[0].trim() + " and " + parts[1].trim()
                : "\"" + rule.getComparisonValue() + "\"";
    }
}
