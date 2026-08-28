package com.finora.service;

import com.finora.dto.CategoryUsageDto;
import com.finora.entity.Category;
import com.finora.entity.CategoryRule;
import com.finora.exception.ApiException;
import com.finora.repository.BudgetRepository;
import com.finora.repository.CategoryRepository;
import com.finora.repository.CategoryRuleRepository;
import com.finora.repository.TransactionRepository;
import com.finora.security.OwnershipGuard;
import com.finora.util.CategoryPalette;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * User-facing category CRUD. Distinct from CategorizationService.resolveOrCreateCategory, which
 * stays the internal server-side find-or-create path used by import/rule-matching/bulk-recategorize
 * and is untouched by this service.
 */
@Service
public class CategoryService {

    private static final int MAX_NAME_LENGTH = 80;

    private final CategoryRepository categoryRepository;
    private final CategoryRuleRepository categoryRuleRepository;
    private final TransactionRepository transactionRepository;
    private final BudgetRepository budgetRepository;
    private final AuditService auditService;

    public CategoryService(CategoryRepository categoryRepository,
                            CategoryRuleRepository categoryRuleRepository,
                            TransactionRepository transactionRepository,
                            BudgetRepository budgetRepository,
                            AuditService auditService) {
        this.categoryRepository = categoryRepository;
        this.categoryRuleRepository = categoryRuleRepository;
        this.transactionRepository = transactionRepository;
        this.budgetRepository = budgetRepository;
        this.auditService = auditService;
    }

    @Transactional
    public Category create(UUID userId, String name, String icon, String color) {
        String safeName = validateName(name);
        validateNoDuplicate(userId, safeName, null);
        String safeIcon = icon == null ? "tag" : validateIcon(icon);
        String safeColor = color == null ? "gray" : validateColor(color);

        Category c = new Category();
        c.setUserId(userId);
        c.setName(safeName);
        c.setSystem(false);
        c.setIcon(safeIcon);
        c.setColor(safeColor);
        Category saved = categoryRepository.save(c);

        auditService.record(userId, "CATEGORY_CREATED", "Category", saved.getId(),
                Map.of("name", safeName));
        return saved;
    }

    @Transactional
    public Category rename(UUID userId, UUID categoryId, String newName, String icon, String color) {
        Category category = OwnershipGuard.requireOwned(
                categoryRepository.findById(categoryId), Category::getUserId, userId, "Category");
        if (category.isSystem()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This is a system category and can't be renamed.");
        }

        String oldName = category.getName();
        if (newName != null && !newName.isBlank()) {
            String safeName = validateName(newName);
            if (!safeName.equalsIgnoreCase(oldName)) {
                validateNoDuplicate(userId, safeName, categoryId);
            }
            category.setName(safeName);
        }
        if (icon != null) category.setIcon(validateIcon(icon));
        if (color != null) category.setColor(validateColor(color));
        Category saved = categoryRepository.save(category);

        if (!oldName.equalsIgnoreCase(saved.getName())) {
            List<CategoryRule> affected = categoryRuleRepository
                    .findByUserIdAndActionTypeInAndActionValueIgnoreCase(userId,
                            List.of(CategoryRule.ActionType.ASSIGN_CATEGORY, CategoryRule.ActionType.MARK_INVESTMENT),
                            oldName);
            for (CategoryRule rule : affected) {
                rule.setActionValue(saved.getName());
                categoryRuleRepository.save(rule);
            }
        }

        auditService.record(userId, "CATEGORY_RENAMED", "Category", saved.getId(),
                Map.of("oldName", oldName, "newName", saved.getName()));
        return saved;
    }

    @Transactional(readOnly = true)
    public CategoryUsageDto usage(UUID userId, UUID categoryId) {
        Category category = OwnershipGuard.requireOwned(
                categoryRepository.findById(categoryId), Category::getUserId, userId, "Category");
        long transactionCount = transactionRepository.countByUserIdAndCategoryId(userId, categoryId);
        boolean hasBudget = budgetRepository.findByUserIdAndCategoryId(userId, categoryId).isPresent();
        long ruleCount = categoryRuleRepository.findByUserIdAndActionTypeInAndActionValueIgnoreCase(
                userId,
                List.of(CategoryRule.ActionType.ASSIGN_CATEGORY, CategoryRule.ActionType.MARK_INVESTMENT),
                category.getName()).size();
        return new CategoryUsageDto(transactionCount, hasBudget, ruleCount);
    }

    @Transactional
    public void delete(UUID userId, UUID categoryId, UUID reassignTo) {
        Category category = OwnershipGuard.requireOwned(
                categoryRepository.findById(categoryId), Category::getUserId, userId, "Category");
        if (category.isSystem()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "This is a system category and can't be deleted.");
        }

        long transactionCount = transactionRepository.countByUserIdAndCategoryId(userId, categoryId);
        var existingBudget = budgetRepository.findByUserIdAndCategoryId(userId, categoryId);
        List<CategoryRule> affectedRules = categoryRuleRepository
                .findByUserIdAndActionTypeInAndActionValueIgnoreCase(userId,
                        List.of(CategoryRule.ActionType.ASSIGN_CATEGORY, CategoryRule.ActionType.MARK_INVESTMENT),
                        category.getName());
        boolean hasDependents = transactionCount > 0 || existingBudget.isPresent() || !affectedRules.isEmpty();

        if (hasDependents) {
            if (reassignTo == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                        "This category is in use — pick a category to reassign it to before deleting.");
            }
            Category target = OwnershipGuard.requireOwned(
                    categoryRepository.findById(reassignTo), Category::getUserId, userId, "Category");

            transactionRepository.reassignCategory(userId, categoryId, target.getId());
            existingBudget.ifPresent(budgetRepository::delete);
            for (CategoryRule rule : affectedRules) {
                rule.setActionValue(target.getName());
                categoryRuleRepository.save(rule);
            }
        }

        auditService.record(userId, "CATEGORY_DELETED", "Category", categoryId,
                Map.of("name", category.getName(), "transactionCount", transactionCount,
                        "reassignedTo", reassignTo == null ? "" : reassignTo.toString()));
        categoryRepository.delete(category);
    }

    private String validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Category name can't be blank.");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Category name can't be longer than " + MAX_NAME_LENGTH + " characters.");
        }
        return trimmed;
    }

    private void validateNoDuplicate(UUID userId, String name, UUID excludingCategoryId) {
        List<Category> matches = categoryRepository.findByUserIdAndNameIgnoreCaseOrderByIdAsc(userId, name);
        boolean collides = matches.stream()
                .anyMatch(m -> excludingCategoryId == null || !excludingCategoryId.equals(m.getId()));
        if (collides) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "You already have a category named \"" + name + "\".");
        }
    }

    private String validateIcon(String icon) {
        if (!CategoryPalette.isValidIcon(icon)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "\"" + icon + "\" isn't a supported icon.");
        }
        return icon;
    }

    private String validateColor(String color) {
        if (!CategoryPalette.isValidColor(color)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "\"" + color + "\" isn't a supported color.");
        }
        return color;
    }
}
