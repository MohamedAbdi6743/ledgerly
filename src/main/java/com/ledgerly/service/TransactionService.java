package com.ledgerly.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerly.dto.TransactionResponse;
import com.ledgerly.entity.AppUser;
import com.ledgerly.entity.JournalTransaction;
import com.ledgerly.exception.NotFoundException;
import com.ledgerly.repository.JournalTransactionRepository;
import com.ledgerly.repository.LedgerEntryRepository;

/** Looking up one transaction by its reference. */
@Service
public class TransactionService {

	private final JournalTransactionRepository journalTransactionRepository;
	private final LedgerEntryRepository ledgerEntryRepository;
	private final UserService userService;

	public TransactionService(JournalTransactionRepository journalTransactionRepository,
			LedgerEntryRepository ledgerEntryRepository, UserService userService) {
		this.journalTransactionRepository = journalTransactionRepository;
		this.ledgerEntryRepository = ledgerEntryRepository;
		this.userService = userService;
	}

	/** Staff can see any transaction; a member only one that touches their own account. */
	@Transactional(readOnly = true)
	public TransactionResponse get(String reference, String viewerEmail) {
		AppUser viewer = userService.getByEmail(viewerEmail);
		JournalTransaction transaction = journalTransactionRepository.findByReference(reference)
				.orElseThrow(() -> new NotFoundException("Transaction not found."));

		boolean allowed = viewer.getRole().isStaff()
				|| (transaction.getFromAccount() != null && AccountService.isOwner(transaction.getFromAccount(), viewer))
				|| (transaction.getToAccount() != null && AccountService.isOwner(transaction.getToAccount(), viewer));
		if (!allowed) {
			throw new NotFoundException("Transaction not found.");
		}
		return TransactionResponse.from(transaction, ledgerEntryRepository.findByTransactionIdOrderByIdAsc(transaction.getId()));
	}

}
