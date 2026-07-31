package com.finora.repository;

import com.finora.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    List<Account> findByUserId(UUID userId);
    List<Account> findByUserIdAndAccountType(UUID userId, Account.Type type);

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
