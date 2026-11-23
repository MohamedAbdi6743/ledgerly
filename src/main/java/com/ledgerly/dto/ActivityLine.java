package com.ledgerly.dto;

import java.time.Instant;

import com.ledgerly.entity.LedgerEntry;
import com.ledgerly.entity.TransactionType;

/** One line of an account's history: what happened, the amount, and the balance right after. */
public record ActivityLine(String reference, TransactionType type, String amount, String balanceAfter,
		String description, Instant postedAt) {

	public static ActivityLine from(LedgerEntry entry) {
		return new ActivityLine(entry.getTransaction().getReference(), entry.getTransaction().getType(),
				Money.toText(entry.getAmount()), Money.toText(entry.getBalanceAfter()),
				entry.getTransaction().getDescription(), entry.getCreatedAt());
	}

}
