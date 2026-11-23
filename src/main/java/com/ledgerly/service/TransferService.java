package com.ledgerly.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerly.dto.Money;
import com.ledgerly.dto.TransactionResponse;
import com.ledgerly.dto.TransferRequest;
import com.ledgerly.entity.Account;
import com.ledgerly.entity.AppUser;
import com.ledgerly.entity.JournalTransaction;
import com.ledgerly.entity.LedgerEntry;
import com.ledgerly.entity.TransactionType;
import com.ledgerly.exception.BadRequestException;
import com.ledgerly.exception.NotFoundException;
import com.ledgerly.repository.AccountRepository;
import com.ledgerly.repository.JournalTransactionRepository;
import com.ledgerly.repository.LedgerEntryRepository;
import com.ledgerly.repository.UserRepository;
import com.ledgerly.service.DailyTransferLimit.Window;
import com.ledgerly.service.LedgerService.Leg;
import com.ledgerly.service.PaymentService.PaymentResult;

/**
 * Money from one member account to another.
 *
 * - A member sends from their own account to any open member account (their own or someone
 *   else's). What they send to other people counts toward a daily limit.
 * - Tellers and managers can move money between any two member accounts, with no daily limit.
 *
 * Locks are always taken in the same order: the sending member first (members only), then the
 * two accounts lowest id first. A fixed order is what makes deadlocks impossible.
 * Transfers of $5,000 or more to someone else wait for a manager (see ApprovalPolicy).
 */
@Service
public class TransferService {

	private static final Logger log = LoggerFactory.getLogger(TransferService.class);

	private final AccountRepository accountRepository;
	private final JournalTransactionRepository journalTransactionRepository;
	private final LedgerEntryRepository ledgerEntryRepository;
	private final UserRepository userRepository;
	private final LedgerService ledgerService;
	private final IdempotencyService idempotencyService;
	private final ReferenceGenerator referenceGenerator;
	private final DailyTransferLimit dailyLimit;
	private final UserService userService;
	private final ApprovalPolicy approvalPolicy;
	private final Clock clock;

	public TransferService(AccountRepository accountRepository,
			JournalTransactionRepository journalTransactionRepository, LedgerEntryRepository ledgerEntryRepository,
			UserRepository userRepository, LedgerService ledgerService, IdempotencyService idempotencyService,
			ReferenceGenerator referenceGenerator, DailyTransferLimit dailyLimit, UserService userService,
			ApprovalPolicy approvalPolicy, Clock clock) {
		this.accountRepository = accountRepository;
		this.journalTransactionRepository = journalTransactionRepository;
		this.ledgerEntryRepository = ledgerEntryRepository;
		this.userRepository = userRepository;
		this.ledgerService = ledgerService;
		this.idempotencyService = idempotencyService;
		this.referenceGenerator = referenceGenerator;
		this.dailyLimit = dailyLimit;
		this.userService = userService;
		this.approvalPolicy = approvalPolicy;
		this.clock = clock;
	}

	@Transactional
	public PaymentResult transfer(TransferRequest request, String idempotencyKey, String userEmail) {
		idempotencyService.requireValidKey(idempotencyKey);
		if (request.fromAccount().equals(request.toAccount())) {
			throw new BadRequestException("Choose two different accounts.");
		}
		AppUser user = userService.getByEmail(userEmail);
		boolean member = !user.getRole().isStaff();
		BigDecimal amount = request.amount().setScale(2);
		String description = blankToNull(request.description());
		String fingerprint = idempotencyService.fingerprint(TransactionType.TRANSFER.name(), request.fromAccount(),
				request.toAccount(), amount.toPlainString(), String.valueOf(description));

		// Lock order, step 1: the sending member. Two transfers by the same person wait for each
		// other here, so the daily-limit check below always sees the other one's amount.
		if (member) {
			userRepository.lockById(user.getId());
		}

		// Lock order, step 2: both accounts, lowest id first (ids only before locking; see PaymentService).
		Long fromId = accountRepository.findIdByAccountNumber(request.fromAccount())
				.orElseThrow(() -> new NotFoundException("Account not found."));
		Long toId = accountRepository.findIdByAccountNumber(request.toAccount())
				.orElseThrow(() -> new NotFoundException("Account not found."));
		Map<Long, Account> locked = ledgerService.lockInIdOrder(List.of(fromId, toId));

		// After the locks, so a retry waits for the original and then finds it.
		Optional<JournalTransaction> previous = idempotencyService.findPrevious(user, idempotencyKey, fingerprint);
		if (previous.isPresent()) {
			return replay(previous.get());
		}

		Account from = locked.get(fromId);
		Account to = locked.get(toId);
		if (member && !AccountService.isOwner(from, user)) {
			// Same answer as for an account that doesn't exist (see AccountService.findVisible).
			throw new NotFoundException("Account not found.");
		}
		if (from.isSystem() || to.isSystem()) {
			throw new BadRequestException("Transfers are only between member accounts. Cash goes through a teller.");
		}
		if (member && !AccountService.isOwner(to, user)) {
			Window today = dailyLimit.today();
			dailyLimit.check(journalTransactionRepository.sumSentToOthers(user.getId(), today.from(), today.to()), amount);
		}

		List<Leg> legs = Legs.transfer(from, to, amount);
		String reference = referenceGenerator.next();
		Instant now = Instant.now(clock);

		// Large transfers to someone else wait for a manager. Pending ones still count toward the
		// daily limit (sumSentToOthers skips only rejected ones).
		if (approvalPolicy.transferNeedsApproval(amount, from, to)) {
			ledgerService.check(TransactionType.TRANSFER, legs);
			JournalTransaction pending = journalTransactionRepository.save(JournalTransaction.pending(reference,
					TransactionType.TRANSFER, amount, from, to, description, user, idempotencyKey, fingerprint, now));
			log.info("User {} requested TRANSFER {} of {} from account id {} to account id {}, waiting for approval",
					user.getId(), reference, Money.toText(amount), fromId, toId);
			return new PaymentResult(TransactionResponse.from(pending, List.of()), false);
		}

		JournalTransaction transaction = journalTransactionRepository.save(JournalTransaction.posted(reference,
				TransactionType.TRANSFER, amount, from, to, description, user, idempotencyKey, fingerprint, now));
		List<LedgerEntry> entries = ledgerService.post(transaction, legs);

		log.info("User {} posted TRANSFER {} of {} from account id {} to account id {}", user.getId(),
				reference, Money.toText(amount), fromId, toId);
		return new PaymentResult(TransactionResponse.from(transaction, entries), false);
	}

	private PaymentResult replay(JournalTransaction original) {
		List<LedgerEntry> entries = ledgerEntryRepository.findByTransactionIdOrderByIdAsc(original.getId());
		return new PaymentResult(TransactionResponse.from(original, entries), true);
	}

	private static String blankToNull(String text) {
		return (text == null || text.isBlank()) ? null : text.strip();
	}

}
