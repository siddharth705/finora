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

        String categoryName = t.getCategoryId() == null ? "this category"
                : categoryRepository.findById(t.getCategoryId()).map(Category::getName).orElse("this category");

        return switch (t.getDecisionSource()) {
            case MANUAL -> new TransactionExplanationDto(
                    "MANUAL", "You set this category yourself.", List.of());
            case USER_RULE -> ruleExplanation(t, "USER_RULE",
                    "Matched a rule you created.");
            case GLOBAL_RULE -> ruleExplanation(t, "GLOBAL_RULE",
                    "Matched one of Finora's built-in rules.");
            case LEARNED_PATTERN -> new TransactionExplanationDto(
                    "LEARNED_PATTERN",
                    "Categorized based on how you've categorized " + merchantPhrase(t) + " before.",
                    List.of("Every time you confirm or correct a category, Finora remembers it for that merchant."));
            case KEYWORD_MATCH -> new TransactionExplanationDto(
                    "KEYWORD_MATCH",
                    "Matched a keyword Finora recognizes in the description.",
                    List.of());
            case FILE_PROVIDED -> new TransactionExplanationDto(
                    "FILE_PROVIDED",
                    "The imported file specified this category directly.",
                    List.of());
            case MERCHANT_DEFAULT -> defaultExplanation(t, categoryName);
        };
    }

    private TransactionExplanationDto ruleExplanation(Transaction t, String source, String fallbackSummary) {
        CategoryRule rule = t.getDecisionRuleId() == null
                ? null : categoryRuleRepository.findById(t.getDecisionRuleId()).orElse(null);
        // A rule can be edited or deleted after it matched -- the transaction it already
        // categorized keeps its decisionSource/decisionRuleId regardless, so this stays the
        // honest answer ("a rule matched, here's what's known about it now") rather than an
        // error when the id no longer resolves to today's rule set.
        if (rule == null) {
            return new TransactionExplanationDto(source, fallbackSummary,
                    List.of("The specific rule is no longer available (it may have been edited or removed since)."));
        }
        String condition = fieldLabel(rule.getField()) + " " + operatorLabel(rule.getOperator())
                + " \"" + rule.getComparisonValue() + "\"";
        String summary = fallbackSummary + " " + condition + " → " + rule.getActionValue() + ".";
        return new TransactionExplanationDto(source, summary,
                List.of("Rule condition: " + condition, "Assigns category: " + rule.getActionValue()));
    }

    private TransactionExplanationDto defaultExplanation(Transaction t, String categoryName) {
        if (t.getSource() == Transaction.Source.GMAIL_IMPORT) {
            return new TransactionExplanationDto("MERCHANT_DEFAULT",
                    "Imported from a Gmail receipt (" + merchantPhrase(t)
                            + "). Finora doesn't auto-detect a category for this merchant yet, so it defaulted to \""
                            + categoryName + "\".",
                    List.of("No rule, learned pattern, or keyword matched this transaction."));
        }
        return new TransactionExplanationDto("MERCHANT_DEFAULT",
                "No rule, learned pattern, or keyword matched, so this defaulted to \"" + categoryName + "\".",
                List.of());
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
}
