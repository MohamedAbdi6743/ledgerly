package com.ledgerly.dto;

import java.time.Instant;
import java.util.List;

import com.ledgerly.entity.Account;
import com.ledgerly.entity.JournalTransaction;
import com.ledgerly.entity.LedgerEntry;
import com.ledgerly.entity.TransactionStatus;
import com.ledgerly.entity.TransactionType;

/**
 * A transaction and its ledger entries. Built only from saved data, so a replayed request
 * (same Idempotency-Key) gets exactly the same body as the original.
 */
public record TransactionResponse(String reference, TransactionType type, TransactionStatus status, String amount,
		String fromAccount, String toAccount, String description, Instant createdAt, Instant postedAt,
		List<EntryLine> entries) {

	public record EntryLine(String accountNumber, String amount) {
	}

	public static TransactionResponse from(JournalTransaction transaction, List<LedgerEntry> entries) {
		return new TransactionResponse(transaction.getReference(), transaction.getType(), transaction.getStatus(),
				Money.toText(transaction.getAmount()), numberOf(transaction.getFromAccount()),
				numberOf(transaction.getToAccount()), transaction.getDescription(), transaction.getCreatedAt(),
				transaction.getPostedAt(),
				entries.stream()
						.map(entry -> new EntryLine(entry.getAccount().getAccountNumber(), Money.toText(entry.getAmount())))
						.toList());
	}

	private static String numberOf(Account account) {
		return (account == null) ? null : account.getAccountNumber();
	}

}
