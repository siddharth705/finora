package com.finora.repository;

import com.finora.entity.WalletLedgerEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface WalletLedgerRepository extends JpaRepository<WalletLedgerEntry, UUID> {

    List<WalletLedgerEntry> findByUserIdOrderByCreatedAtDesc(UUID userId);

    /** Proposal §4: balance is a computed SUM over this table, never a stored field.
     *  {@code COALESCE} because {@code SUM} over zero rows is SQL {@code NULL}, not zero -- a user
     *  with no ledger entries yet must see a real 0, not a null balance. */
    @Query("SELECT COALESCE(SUM(w.amount), 0) FROM WalletLedgerEntry w WHERE w.userId = :userId")
    BigDecimal sumAmountByUserId(@Param("userId") UUID userId);

    /** AccountPurgeSweepService. */
    void deleteByUserId(UUID userId);
}
