package com.ledgerly.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerly.dto.PendingRequestResponse;
import com.ledgerly.dto.TransactionResponse;
import com.ledgerly.entity.Account;
import com.ledgerly.entity.AppUser;
import com.ledgerly.entity.JournalTransaction;
import com.ledgerly.entity.LedgerEntry;
import com.ledgerly.entity.TransactionType;
import com.ledgerly.exception.BusinessRuleException;
import com.ledgerly.exception.ForbiddenException;
import com.ledgerly.exception.NotFoundException;
import com.ledgerly.repository.AccountRepository;
import com.ledgerly.repository.JournalTransactionRepository;
import com.ledgerly.service.LedgerService.Leg;

/**
 * Maker-checker: a manager approves or rejects requests that were too large to post right away.
 * Whoever made a request can never decide on it (the four-eyes rule), so one person alone can't
 * move a large amount.
 *
 * Lock order: the request's own row first, then its accounts lowest id first. Locking the request
 * is what stops two managers approving the same one at the same moment and posting it twice.
 */
@Service
public class ApprovalService {

	private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);

	private final JournalTransactionRepository journalTransactionRepository;
	private final AccountRepository accountRepository;
	private final LedgerService ledgerService;
	private final UserService userService;
	private final AuditService auditService;
	private final Clock clock;

	public ApprovalService(JournalTransactionRepository journalTransactionRepository,
			AccountRepository accountRepository, LedgerService ledgerService, UserService userService,
			AuditService auditService, Clock clock) {
		this.journalTransactionRepository = journalTransactionRepository;
		this.accountRepository = accountRepository;
		this.ledgerService = ledgerService;
		this.userService = userService;
		this.auditService = auditService;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<PendingRequestResponse> pending() {
		return journalTransactionRepository.findPending().stream()
				.map(transaction -> new PendingRequestResponse(TransactionResponse.from(transaction, List.of()),
						transaction.getCreatedBy().getFullName(), transaction.getCreatedBy().getEmail()))
				.toList();
	}

	@Transactional
	public TransactionResponse approve(String reference, String managerEmail) {
		AppUser manager = userService.getByEmail(managerEmail);
		JournalTransaction request = lockRequest(reference);
		requireDecidable(request, manager);

		// Balances may have changed since the request was made, so every rule runs again here.
		// If the money is no longer there, post() refuses before changing anything, and the
		// request stays pending. Only once the entries are written is it marked approved.
		List<Leg> legs = legsFor(request);
		List<LedgerEntry> entries = ledgerService.post(request, legs);
		request.approve(manager, Instant.now(clock));

		auditService.record(manager, "TRANSACTION_APPROVED", "TRANSACTION", reference, null);
		log.info("User {} approved {}", manager.getId(), reference);
		return TransactionResponse.from(request, entries);
	}

	@Transactional
	public TransactionResponse reject(String reference, String reason, String managerEmail) {
		AppUser manager = userService.getByEmail(managerEmail);
		JournalTransaction request = lockRequest(reference);
		requireDecidable(request, manager);

		request.reject(manager, reason.strip());

		auditService.record(manager, "TRANSACTION_REJECTED", "TRANSACTION", reference, reason.strip());
		log.info("User {} rejected {}", manager.getId(), reference);
		return TransactionResponse.from(request, List.of());
	}

	private JournalTransaction lockRequest(String reference) {
		Long id = journalTransactionRepository.findIdByReference(reference)
				.orElseThrow(() -> new NotFoundException("Transaction not found."));
		return journalTransactionRepository.findByIdForUpdate(id)
				.orElseThrow(() -> new NotFoundException("Transaction not found."));
	}

	private static void requireDecidable(JournalTransaction request, AppUser manager) {
		if (!request.isPending()) {
			throw new BusinessRuleException("This request isn't waiting for approval (it's "
					+ request.getStatus().name().toLowerCase().replace('_', ' ') + ").");
		}
		// Also enforced by the database: CHECK (approved_by <> created_by).
		if (request.getCreatedBy().getId().equals(manager.getId())) {
			throw new ForbiddenException("You can't approve or reject your own request. Another manager has to.");
		}
	}

	/** Locks the request's accounts (lowest id first) and builds the entries it will write. */
	private List<Leg> legsFor(JournalTransaction request) {
		List<Long> ids = new ArrayList<>();
		if (request.getFromAccount() != null) {
			ids.add(request.getFromAccount().getId());
		}
		if (request.getToAccount() != null) {
			ids.add(request.getToAccount().getId());
		}
		Long cashId = null;
		if (request.getType() == TransactionType.WITHDRAWAL) {
			cashId = accountRepository.findIdByAccountNumber(PaymentService.CASH_ON_HAND)
					.orElseThrow(() -> new IllegalStateException("The Cash on hand account is missing."));
			ids.add(cashId);
		}
		Map<Long, Account> locked = ledgerService.lockInIdOrder(ids);

		return switch (request.getType()) {
			case WITHDRAWAL -> Legs.withdrawal(locked.get(request.getFromAccount().getId()), locked.get(cashId),
					request.getAmount());
			case TRANSFER -> Legs.transfer(locked.get(request.getFromAccount().getId()),
					locked.get(request.getToAccount().getId()), request.getAmount());
			default -> throw new IllegalStateException("A " + request.getType() + " never waits for approval.");
		};
	}

}
