package com.ledgerly.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerly.entity.Account;
import com.ledgerly.entity.JournalTransaction;
import com.ledgerly.entity.LedgerEntry;
import com.ledgerly.exception.BusinessRuleException;
import com.ledgerly.exception.NotFoundException;
import com.ledgerly.repository.AccountRepository;
import com.ledgerly.repository.LedgerEntryRepository;

/**
 * The only code that writes ledger entries or changes a balance.
 *
 * Callers lock the accounts first (lockInIdOrder), then call post() with the legs of one
 * transaction. post() refuses anything that doesn't add up to zero, so a bug elsewhere can't
 * create or destroy money.
 */
@Service
public class LedgerService {

	/** One side of a transaction: this much into (positive) or out of (negative) this account. */
	public record Leg(Account account, BigDecimal amount) {
	}

	private final AccountRepository accountRepository;
	private final LedgerEntryRepository ledgerEntryRepository;
	private final Clock clock;

	public LedgerService(AccountRepository accountRepository, LedgerEntryRepository ledgerEntryRepository, Clock clock) {
		this.accountRepository = accountRepository;
		this.ledgerEntryRepository = ledgerEntryRepository;
		this.clock = clock;
	}

	/**
	 * Locks the accounts (SELECT ... FOR UPDATE) lowest id first. If every caller locks in the
	 * same order, two requests can never each hold a lock the other is waiting for (a deadlock).
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public Map<Long, Account> lockInIdOrder(Collection<Long> accountIds) {
		Map<Long, Account> locked = new LinkedHashMap<>();
		accountIds.stream().distinct().sorted().forEach(id -> locked.put(id,
				accountRepository.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Account not found."))));
		return locked;
	}

	/**
	 * Writes the entries for one transaction and updates the cached balances.
	 * The accounts must already be locked by the caller, in this same database transaction.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	public List<LedgerEntry> post(JournalTransaction transaction, List<Leg> legs) {
		checkBalanced(legs);

		// Check every leg before changing anything, so the rules read clearly.
		for (Leg leg : legs) {
			Account account = leg.account();
			if (account.isSystem()) {
				continue; // "Cash on hand" may go negative; it stands for cash that came from outside.
			}
			if (!account.isOpen()) {
				throw new BusinessRuleException("Account " + account.getAccountNumber() + " is frozen.");
			}
			if (account.getBalance().add(leg.amount()).signum() < 0) {
				throw new BusinessRuleException("Insufficient funds in account " + account.getAccountNumber() + ".");
			}
		}

		Instant now = Instant.now(clock);
		List<LedgerEntry> entries = new ArrayList<>();
		for (Leg leg : legs) {
			BigDecimal balanceAfter = leg.account().applyEntry(leg.amount());
			entries.add(new LedgerEntry(transaction, leg.account(), leg.amount(), balanceAfter, now));
		}
		return ledgerEntryRepository.saveAll(entries);
	}

	/**
	 * Programming errors, not user errors: the services build the legs, so a bad set means a bug.
	 * IllegalStateException becomes a 500 and rolls everything back.
	 */
	static void checkBalanced(List<Leg> legs) {
		if (legs.size() < 2) {
			throw new IllegalStateException("A transaction needs at least two entries.");
		}
		BigDecimal sum = BigDecimal.ZERO;
		for (Leg leg : legs) {
			if (leg.amount().signum() == 0) {
				throw new IllegalStateException("A ledger entry can't be zero.");
			}
			if (leg.amount().stripTrailingZeros().scale() > 2) {
				throw new IllegalStateException("A ledger entry can't have fractions of a cent.");
			}
			sum = sum.add(leg.amount());
		}
		if (sum.signum() != 0) {
			throw new IllegalStateException("Entries must add up to zero, but they add up to " + sum + ".");
		}
	}

}
