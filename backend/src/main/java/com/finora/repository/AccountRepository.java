package com.finora.repository;

import com.finora.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    List<Account> findByUserId(UUID userId);
    List<Account> findByUserIdAndAccountType(UUID userId, Account.Type type);

    // GmailReviewService's find-or-create for the one shared "Gmail receipts" account (C5.4) --
    // by exact name rather than a dedicated marker column, since that name is already the fixed
    // suggestedName GmailStagingBridge's unknownAccount() has produced for every receipt since
    // C5-B. findFirst rather than assuming uniqueness: nothing enforces a user can't also have an
    // unrelated account of the same name, and a derived query throwing on more than one match
    // would turn that harmless coincidence into a broken approval.
    Optional<Account> findFirstByUserIdAndName(UUID userId, String name);

    // Backs the admin User detail view's "N accounts" stat (AdminUserService.getUser). A derived
    // query, so it's still filtered by @SQLRestriction like every other non-native lookup on this
    // entity -- counts active accounts only, which is what an admin reviewing a user's footprint
    // actually wants, not a historical total that includes accounts they've since deleted.
    long countByUserId(UUID userId);

    // Guards BankManagementService.deleteCustom -- a bank still assigned to at least one account
    // can't be deleted out from under those accounts (they'd be left with a bank_id that resolves
    // to nothing, silently falling back to a generic "OTHER" display -- confusing, not catastrophic,
    // but easy to just prevent outright).
    long countByBankId(String bankId);

    // Native query on purpose: Account carries @SQLRestriction("deleted_at IS NULL"), which
    // Hibernate applies to every HQL/derived-query/Criteria lookup against this entity — a plain
    // JPQL @Query would still get filtered. Statement History's 7-day "deleted account still
    // visible" grace period (see StatementImportService.listGroupedByAccount) needs the
    // soft-deleted rows too, so this goes around the restriction via raw SQL instead.
    @Query(value = "SELECT * FROM accounts WHERE user_id = :userId", nativeQuery = true)
    List<Account> findByUserIdIncludingDeleted(@Param("userId") UUID userId);
}
