package com.ledgerly.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.ledgerly.entity.JournalTransaction;

public interface JournalTransactionRepository extends JpaRepository<JournalTransaction, Long> {

	Optional<JournalTransaction> findByReference(String reference);

	/** A previous request by the same person with the same Idempotency-Key, if any. */
	Optional<JournalTransaction> findByCreatedByIdAndIdempotencyKey(Long createdById, String idempotencyKey);

}
