package com.ledgerly.repository;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ledgerly.entity.LedgerEntry;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

	List<LedgerEntry> findByTransactionIdOrderByIdAsc(Long transactionId);

	/** Most recent first. */
	@Query("SELECT e FROM LedgerEntry e JOIN FETCH e.transaction WHERE e.account.id = :accountId ORDER BY e.id DESC")
	List<LedgerEntry> findRecentForAccount(@Param("accountId") Long accountId, Pageable limit);

	/** The true balance of an account: the sum of all its entries. */
	@Query("SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntry e WHERE e.account.id = :accountId")
	BigDecimal sumForAccount(@Param("accountId") Long accountId);

	/** Sum of the whole ledger. Double-entry means this must always be zero. */
	@Query("SELECT COALESCE(SUM(e.amount), 0) FROM LedgerEntry e")
	BigDecimal sumOfAllEntries();

}
