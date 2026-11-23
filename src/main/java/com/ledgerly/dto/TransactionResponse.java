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
 *
 * reverses:       for a reversal, the reference of the transaction it undoes.
 * reversedBy:     for a reversed transaction, the reference of the reversal (lookups only).
 * decisionReason: why a manager rejected it.
 * A pending or rejected transaction has no entries: no money has moved.
 */
public record TransactionResponse(String reference, TransactionType type, TransactionStatus status, String amount,
		String fromAccount, String toAccount, String description, String reverses, String reversedBy,
		String decisionReason, Instant createdAt, Instant postedAt, List<EntryLine> entries) {

	public record EntryLine(String accountNumber, String amount) {
	}

	public static TransactionResponse from(JournalTransaction transaction, List<LedgerEntry> entries) {
		return from(transaction, entries, null);
	}

	public static TransactionResponse from(JournalTransaction transaction, List<LedgerEntry> entries,
			String reversedBy) {
		return new TransactionResponse(transaction.getReference(), transaction.getType(), transaction.getStatus(),
				Money.toText(transaction.getAmount()), numberOf(transaction.getFromAccount()),
				numberOf(transaction.getToAccount()), transaction.getDescription(),
				(transaction.getReverses() == null) ? null : transaction.getReverses().getReference(),
				reversedBy, transaction.getDecisionReason(), transaction.getCreatedAt(), transaction.getPostedAt(),
				entries.stream()
						.map(entry -> new EntryLine(entry.getAccount().getAccountNumber(), Money.toText(entry.getAmount())))
						.toList());
	}

	private static String numberOf(Account account) {
		return (account == null) ? null : account.getAccountNumber();
	}

}
