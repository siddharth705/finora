package com.finora.repository;

import com.finora.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryRepository extends JpaRepository<Category, UUID> {
    List<Category> findByUserId(UUID userId);
    Optional<Category> findByUserIdAndName(UUID userId, String name);

    /** AccountPurgeSweepService -- called after every table that FK's to categories (transactions,
     *  budgets, merchant_category_learning, merchant_learning_events, merchant_learning_audit) is
     *  already empty. Hard delete, no soft-delete concern on this entity. */
    void deleteByUserId(UUID userId);
}
