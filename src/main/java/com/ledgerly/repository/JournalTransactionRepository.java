package com.ledgerly.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ledgerly.entity.JournalTransaction;

public interface JournalTransactionRepository extends JpaRepository<JournalTransaction, Long> {

	Optional<JournalTransaction> findByReference(String reference);

	/** A previous request by the same person with the same Idempotency-Key, if any. */
	Optional<JournalTransaction> findByCreatedByIdAndIdempotencyKey(Long createdById, String idempotencyKey);

	/**
	 * How much a member has sent today to accounts they don't own. Transfers between their own
	 * accounts don't count, and neither do rejected ones (from milestone 5).
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
