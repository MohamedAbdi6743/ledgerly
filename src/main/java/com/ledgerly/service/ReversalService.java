package com.ledgerly.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerly.dto.TransactionResponse;
import com.ledgerly.entity.Account;
import com.ledgerly.entity.AppUser;
import com.ledgerly.entity.JournalTransaction;
import com.ledgerly.entity.LedgerEntry;
import com.ledgerly.entity.TransactionStatus;
import com.ledgerly.entity.TransactionType;
import com.ledgerly.exception.BusinessRuleException;
import com.ledgerly.exception.NotFoundException;
import com.ledgerly.repository.JournalTransactionRepository;
import com.ledgerly.repository.LedgerEntryRepository;
import com.ledgerly.service.PaymentService.PaymentResult;

/**
 * Fixing a mistake without editing history. A reversal is a new transaction whose entries are
 * the original's with the signs flipped, linked to the original, with a reason. The original
 * stays exactly as it was, so anyone reading the history later sees both.
 *
 * Lock order: the original transaction's row, then its accounts lowest id first (same as approvals).
 */
@Service
public class ReversalService {

	private static final Logger log = LoggerFactory.getLogger(ReversalService.class);

	private final JournalTransactionRepository journalTransactionRepository;
	private final LedgerEntryRepository ledgerEntryRepository;
	private final LedgerService ledgerService;
	private final IdempotencyService idempotencyService;
	private final ReferenceGenerator referenceGenerator;
	private final UserService userService;
	private final AuditService auditService;
	private final Clock clock;

	public ReversalService(JournalTransactionRepository journalTransactionRepository,
			LedgerEntryRepository ledgerEntryRepository, LedgerService ledgerService,
			IdempotencyService idempotencyService, ReferenceGenerator referenceGenerator, UserService userService,
			AuditService auditService, Clock clock) {
		this.journalTransactionRepository = journalTransactionRepository;
		this.ledgerEntryRepository = ledgerEntryRepository;
		this.ledgerService = ledgerService;
		this.idempotencyService = idempotencyService;
		this.referenceGenerator = referenceGenerator;
		this.userService = userService;
		this.auditService = auditService;
		this.clock = clock;
	}

	@Transactional
	public PaymentResult reverse(String reference, String reason, String idempotencyKey, String managerEmail) {
		idempotencyService.requireValidKey(idempotencyKey);
		AppUser manager = userService.getByEmail(managerEmail);
		String cleanReason = reason.strip();
		String fingerprint = idempotencyService.fingerprint(TransactionType.REVERSAL.name(), reference, cleanReason);

		Long originalId = journalTransactionRepository.findIdByReference(reference)
				.orElseThrow(() -> new NotFoundException("Transaction not found."));
		JournalTransaction original = journalTransactionRepository.findByIdForUpdate(originalId)
				.orElseThrow(() -> new NotFoundException("Transaction not found."));
		List<LedgerEntry> originalEntries = ledgerEntryRepository.findByTransactionIdOrderByIdAsc(originalId);
		Map<Long, Account> locked = ledgerService.lockInIdOrder(
				originalEntries.stream().map(entry -> entry.getAccount().getId()).toList());

		Optional<JournalTransaction> previous = idempotencyService.findPrevious(manager, idempotencyKey, fingerprint);
		if (previous.isPresent()) {
			return new PaymentResult(TransactionResponse.from(previous.get(),
					ledgerEntryRepository.findByTransactionIdOrderByIdAsc(previous.get().getId())), true);
		}

		if (original.getStatus() == TransactionStatus.PENDING_APPROVAL) {
			throw new BusinessRuleException("This request hasn't been approved yet, so nothing moved. Reject it instead.");
		}
		if (original.getStatus() != TransactionStatus.POSTED) {
			throw new BusinessRuleException("Only posted transactions can be reversed.");
		}
		if (original.getType() == TransactionType.REVERSAL) {
			throw new BusinessRuleException("A reversal can't be reversed. Post a new transaction instead.");
		}
		Optional<JournalTransaction> earlier = journalTransactionRepository.findByReversesId(originalId);
		if (earlier.isPresent()) {
			// Also enforced by the database: reverses_id is UNIQUE.
			throw new BusinessRuleException("This transaction was already reversed by " + earlier.get().getReference() + ".");
		}

		// If the money has since been spent (a deposit reversed after a withdrawal), post() refuses
		// with "insufficient funds" rather than letting the member's account go negative.
		JournalTransaction reversal = journalTransactionRepository.save(JournalTransaction.reversalOf(original,
				referenceGenerator.next(), cleanReason, manager, idempotencyKey, fingerprint, Instant.now(clock)));
		List<LedgerEntry> entries = ledgerService.post(reversal, Legs.reversal(originalEntries, locked));

		auditService.record(manager, "TRANSACTION_REVERSED", "TRANSACTION", reference,
				"Reversed by " + reversal.getReference() + ": " + cleanReason);
		log.info("User {} reversed {} with {}", manager.getId(), reference, reversal.getReference());
		return new PaymentResult(TransactionResponse.from(reversal, entries), false);
	}

}
