package com.finora.repository;

import com.finora.entity.Bank;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BankRepository extends JpaRepository<Bank, String> {
    List<Bank> findAllByOrderByOfficialNameAsc();

    /** Global Search (AdminSearchService) -- LIKE match against both name fields, same
     *  case-insensitive convention UserRepository.search/TransactionRepository.search already
     *  use elsewhere in this codebase. Only searches admin-added custom banks in this table --
     *  the much larger built-in BankRegistry catalog is a static in-memory list, not a database
     *  table, and isn't part of this admin search's scope (see AdminSearchService's class
     *  comment for why banksApi.search already covers that catalog separately, for the account
     *  picker use case, not this one). */
    @Query("""
        SELECT b FROM Bank b
        WHERE LOWER(b.officialName) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
           OR LOWER(b.shortName) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
        ORDER BY b.officialName ASC
        """)
    List<Bank> searchByName(@Param("q") String q, Pageable pageable);
}
