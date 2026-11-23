package com.ledgerly.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.ledgerly.entity.Account;

import jakarta.persistence.LockModeType;

public interface AccountRepository extends JpaRepository<Account, Long> {

	Optional<Account> findByAccountNumber(String accountNumber);

	List<Account> findByOwnerIdOrderByIdAsc(Long ownerId);

	/**
	 * Only the id, not the account itself. Money code looks up ids first and then locks by id.
	 * If it loaded the whole account first, Hibernate would keep that (possibly stale) copy and
	 * hand it back again from the locking query, so the balance check could use old data.
	 */
	@Query("SELECT a.id FROM Account a WHERE a.accountNumber = :accountNumber")
	Optional<Long> findIdByAccountNumber(@Param("accountNumber") String accountNumber);

	/**
	 * Loads an account and LOCKS its row until the transaction ends (SELECT ... FOR UPDATE).
	 * Anyone else locking the same account waits, so two requests can't both spend the same money.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT a FROM Account a WHERE a.id = :id")
	Optional<Account> findByIdForUpdate(@Param("id") Long id);

	@Query(value = "SELECT nextval('account_number_seq')", nativeQuery = true)
	long nextAccountNumber();

}
