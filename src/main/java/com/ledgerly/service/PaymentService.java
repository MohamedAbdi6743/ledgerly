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

import com.ledgerly.dto.CashRequest;
import com.ledgerly.dto.Money;
import com.ledgerly.dto.TransactionResponse;
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
import com.ledgerly.service.LedgerService.Leg;

/**
 * Cash deposits and withdrawals at the counter. Transfers are in TransferService.
 * Withdrawals of $5,000 or more wait for a manager (see ApprovalPolicy and ApprovalService).
 */
@Service
public class PaymentService {

	/** The system account that stands for the physical cash drawer. Created by the V1 migration. */
	public static final String CASH_ON_HAND = "000000000001";

	private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

	/** What happened, and whether it was a retry of an earlier request (nothing new was done). */
	public record PaymentResult(TransactionResponse transaction, boolean replayed) {
	}

	private final AccountRepository accountRepository;
	private final JournalTransactionRepository journalTransactionRepository;
	private final LedgerEntryRepository ledgerEntryRepository;
	private final LedgerService ledgerService;
	private final IdempotencyService idempotencyService;
	private final ReferenceGenerator referenceGenerator;
	private final UserService userService;
	private final ApprovalPolicy approvalPolicy;
	private final Clock clock;

	public PaymentService(AccountRepository accountRepository, JournalTransactionRepository journalTransactionRepository,
			LedgerEntryRepository ledgerEntryRepository, LedgerService ledgerService,
			IdempotencyService idempotencyService, ReferenceGenerator referenceGenerator, UserService userService,
			ApprovalPolicy approvalPolicy, Clock clock) {
		this.accountRepository = accountRepository;
		this.journalTransactionRepository = journalTransactionRepository;
		this.ledgerEntryRepository = ledgerEntryRepository;
		this.ledgerService = ledgerService;
		this.idempotencyService = idempotencyService;
		this.referenceGenerator = referenceGenerator;
		this.userService = userService;
		this.approvalPolicy = approvalPolicy;
		this.clock = clock;
	}

	@Transactional
	public PaymentResult deposit(CashRequest request, String idempotencyKey, String staffEmail) {
		return cash(TransactionType.DEPOSIT, request, idempotencyKey, staffEmail);
	}

	@Transactional
	public PaymentResult withdraw(CashRequest request, String idempotencyKey, String staffEmail) {
		return cash(TransactionType.WITHDRAWAL, request, idempotencyKey, staffEmail);
	}

	private PaymentResult cash(TransactionType type, CashRequest request, String idempotencyKey, String staffEmail) {
		idempotencyService.requireValidKey(idempotencyKey);
		AppUser staff = userService.getByEmail(staffEmail);
		BigDecimal amount = request.amount().setScale(2);
		String description = blankToNull(request.description());
		String fingerprint = idempotencyService.fingerprint(type.name(), request.accountNumber(), amount.toPlainString(),
				String.valueOf(description));

		// Look up ids only, then lock. (Loading the accounts before locking them would leave
		// Hibernate holding a copy that might be stale by the time the lock is granted.)
		Long memberAccountId = accountRepository.findIdByAccountNumber(request.accountNumber())
				.orElseThrow(() -> new NotFoundException("Account not found."));
		Long cashId = accountRepository.findIdByAccountNumber(CASH_ON_HAND)
				.orElseThrow(() -> new IllegalStateException("The Cash on hand account is missing."));
		Map<Long, Account> locked = ledgerService.lockInIdOrder(List.of(memberAccountId, cashId));

		// Checked AFTER the locks on purpose. A retry of the same request locks the same accounts,
		// so it waits here until the first one commits, and then finds it instead of posting twice.
		Optional<JournalTransaction> previous = idempotencyService.findPrevious(staff, idempotencyKey, fingerprint);
		if (previous.isPresent()) {
			return replay(previous.get());
		}

		Account memberAccount = locked.get(memberAccountId);
		Account cash = locked.get(cashId);
		if (memberAccount.isSystem()) {
			throw new BadRequestException("Cash can only go into or out of a member's account.");
		}

		boolean deposit = (type == TransactionType.DEPOSIT);
		Account from = deposit ? null : memberAccount;   // a withdrawal takes money from the member
		Account to = deposit ? memberAccount : null;     // a deposit puts money into the member's account
		List<Leg> legs = deposit ? Legs.deposit(memberAccount, cash, amount) : Legs.withdrawal(memberAccount, cash, amount);
		String reference = referenceGenerator.next();
		Instant now = Instant.now(clock);

		// Large withdrawals wait for a manager. Check now that it could go through, so a request
		// that never could doesn't sit in the queue, but move no money yet.
		if (!deposit && approvalPolicy.withdrawalNeedsApproval(amount)) {
			ledgerService.check(type, legs);
			JournalTransaction pending = journalTransactionRepository.save(JournalTransaction.pending(
					reference, type, amount, from, to, description, staff, idempotencyKey, fingerprint, now));
			log.info("User {} requested {} {} of {} on account id {}, waiting for approval", staff.getId(), type,
					reference, Money.toText(amount), memberAccountId);
			return new PaymentResult(TransactionResponse.from(pending, List.of()), false);
		}

		JournalTransaction transaction = journalTransactionRepository.save(JournalTransaction.posted(
				reference, type, amount, from, to, description, staff, idempotencyKey, fingerprint, now));
		List<LedgerEntry> entries = ledgerService.post(transaction, legs);

		log.info("User {} posted {} {} of {} on account id {}", staff.getId(), type, reference,
				Money.toText(amount), memberAccountId);
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
