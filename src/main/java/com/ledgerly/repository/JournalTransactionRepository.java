package com.ledgerly.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ledgerly.entity.JournalTransaction;

import jakarta.persistence.LockModeType;

public interface JournalTransactionRepository extends JpaRepository<JournalTransaction, Long> {

	Optional<JournalTransaction> findByReference(String reference);

	/** Id only, so the row can be locked before Hibernate holds a copy (same idea as for accounts). */
	@Query("SELECT t.id FROM JournalTransaction t WHERE t.reference = :reference")
	Optional<Long> findIdByReference(@Param("reference") String reference);

	/**
	 * Loads a transaction and locks its row. Approving, rejecting and reversing all lock the
	 * transaction first, so two managers clicking at the same moment can't both act on it.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT t FROM JournalTransaction t WHERE t.id = :id")
	Optional<JournalTransaction> findByIdForUpdate(@Param("id") Long id);

	/** The manager's queue, oldest first. */
	@Query("""
			SELECT t FROM JournalTransaction t
			WHERE t.status = com.ledgerly.entity.TransactionStatus.PENDING_APPROVAL
			ORDER BY t.createdAt ASC, t.id ASC
			""")
	List<JournalTransaction> findPending();

	boolean existsByReversesId(Long originalId);

	Optional<JournalTransaction> findByReversesId(Long originalId);

	/** A previous request by the same person with the same Idempotency-Key, if any. */
	Optional<JournalTransaction> findByCreatedByIdAndIdempotencyKey(Long createdById, String idempotencyKey);

	/**
	 * How much a member has sent today to accounts they don't own. Transfers between their own
	 * accounts don't count, and neither do rejected ones. Pending ones do.
	 */
	@Query("""
			SELECT COALESCE(SUM(t.amount), 0) FROM JournalTransaction t
			WHERE t.createdBy.id = :memberId
			  AND t.type = com.ledgerly.entity.TransactionType.TRANSFER
			  AND t.status <> com.ledgerly.entity.TransactionStatus.REJECTED
			  AND t.toAccount.owner.id <> :memberId
			  AND t.createdAt >= :from AND t.createdAt < :to
			""")
	BigDecimal sumSentToOthers(@Param("memberId") Long memberId, @Param("from") Instant from, @Param("to") Instant to);

}
