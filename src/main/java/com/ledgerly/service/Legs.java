package com.ledgerly.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.ledgerly.entity.Account;
import com.ledgerly.entity.LedgerEntry;
import com.ledgerly.service.LedgerService.Leg;

/**
 * The entries each kind of transaction writes, in one place. Posting right away, approving a
 * pending request later, and reversing all build them here, so they can't drift apart.
 */
final class Legs {

	private Legs() {
	}

	/** Deposit: member +amount, cash drawer -amount. */
	static List<Leg> deposit(Account member, Account cashOnHand, BigDecimal amount) {
		return List.of(new Leg(member, amount), new Leg(cashOnHand, amount.negate()));
	}

	/** Withdrawal: member -amount, cash drawer +amount. */
	static List<Leg> withdrawal(Account member, Account cashOnHand, BigDecimal amount) {
		return List.of(new Leg(member, amount.negate()), new Leg(cashOnHand, amount));
	}

	/** Transfer: -amount where it leaves, +amount where it arrives. */
	static List<Leg> transfer(Account from, Account to, BigDecimal amount) {
		return List.of(new Leg(from, amount.negate()), new Leg(to, amount));
	}

	/** Reversal: every original entry with the sign flipped, on the same (already locked) accounts. */
	static List<Leg> reversal(List<LedgerEntry> originalEntries, Map<Long, Account> lockedAccounts) {
		return originalEntries.stream()
				.map(entry -> new Leg(lockedAccounts.get(entry.getAccount().getId()), entry.getAmount().negate()))
				.toList();
	}

}
