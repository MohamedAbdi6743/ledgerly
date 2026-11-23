package com.ledgerly.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerly.dto.AccountDetailResponse;
import com.ledgerly.dto.AccountResponse;
import com.ledgerly.dto.ActivityLine;
import com.ledgerly.entity.Account;
import com.ledgerly.entity.AccountStatus;
import com.ledgerly.entity.AccountType;
import com.ledgerly.entity.AppUser;
import com.ledgerly.exception.BadRequestException;
import com.ledgerly.exception.BusinessRuleException;
import com.ledgerly.exception.NotFoundException;
import com.ledgerly.repository.AccountRepository;
import com.ledgerly.repository.LedgerEntryRepository;

/** Opening, viewing, freezing and unfreezing accounts. Moving money is in PaymentService. */
@Service
public class AccountService {

	private static final int RECENT_ACTIVITY = 20;

	private final AccountRepository accountRepository;
	private final LedgerEntryRepository ledgerEntryRepository;
	private final UserService userService;
	private final AuditService auditService;
	private final Clock clock;

	public AccountService(AccountRepository accountRepository, LedgerEntryRepository ledgerEntryRepository,
			UserService userService, AuditService auditService, Clock clock) {
		this.accountRepository = accountRepository;
		this.ledgerEntryRepository = ledgerEntryRepository;
		this.userService = userService;
		this.auditService = auditService;
		this.clock = clock;
	}

	@Transactional
	public AccountResponse openAccount(Long memberId, AccountType type, String name, String staffEmail) {
		if (type == AccountType.SYSTEM) {
			throw new BadRequestException("Only chequing and savings accounts can be opened.");
		}
		AppUser staff = userService.getByEmail(staffEmail);
		AppUser member = userService.getMember(memberId);

		// 1002 + 8 digits from a sequence, e.g. 100200000001. A sequence can't hand out the same
		// number twice, even to two tellers at the same moment.
		String accountNumber = String.format("1002%08d", accountRepository.nextAccountNumber());
		Account account = accountRepository.save(new Account(accountNumber, member, type, name.strip(), Instant.now(clock)));

		auditService.record(staff, "ACCOUNT_OPENED", "ACCOUNT", accountNumber, type + " for member " + member.getMemberNumber());
		return AccountResponse.from(account);
	}

	/** A member's own accounts. */
	@Transactional(readOnly = true)
	public List<AccountResponse> myAccounts(String memberEmail) {
		AppUser member = userService.getByEmail(memberEmail);
		return accountsOf(member.getId());
	}

	/** Any member's accounts, for staff at the counter. */
	@Transactional(readOnly = true)
	public List<AccountResponse> accountsOfMember(Long memberId) {
		return accountsOf(userService.getMember(memberId).getId());
	}

	@Transactional(readOnly = true)
	public AccountDetailResponse getAccount(String accountNumber, String viewerEmail) {
		AppUser viewer = userService.getByEmail(viewerEmail);
		Account account = findVisible(accountNumber, viewer);
		List<ActivityLine> activity = ledgerEntryRepository
				.findRecentForAccount(account.getId(), PageRequest.of(0, RECENT_ACTIVITY))
				.stream().map(ActivityLine::from).toList();
		return new AccountDetailResponse(AccountResponse.from(account), activity);
	}

	@Transactional
	public AccountResponse changeStatus(String accountNumber, AccountStatus newStatus, String reason, String managerEmail) {
		AppUser manager = userService.getByEmail(managerEmail);
		Account account = accountRepository.findByAccountNumber(accountNumber)
				.orElseThrow(() -> new NotFoundException("Account not found."));
		if (account.isSystem()) {
			throw new BadRequestException("System accounts can't be frozen.");
		}
		if (account.getStatus() == newStatus) {
			throw new BusinessRuleException("The account is already " + newStatus.name().toLowerCase() + ".");
		}
		account.changeStatus(newStatus);

		String action = (newStatus == AccountStatus.FROZEN) ? "ACCOUNT_FROZEN" : "ACCOUNT_UNFROZEN";
		auditService.record(manager, action, "ACCOUNT", accountNumber, reason.strip());
		return AccountResponse.from(account);
	}

	/**
	 * Staff can see any account. A member can see only their own; anything else is "not found",
	 * so a member can't find out which account numbers exist by trying them.
	 */
	Account findVisible(String accountNumber, AppUser viewer) {
		Account account = accountRepository.findByAccountNumber(accountNumber)
				.orElseThrow(() -> new NotFoundException("Account not found."));
		if (viewer.getRole().isStaff() || isOwner(account, viewer)) {
			return account;
		}
		throw new NotFoundException("Account not found.");
	}

	static boolean isOwner(Account account, AppUser user) {
		// getOwner() is a lazy proxy; reading its id doesn't load the row.
		return account.getOwner() != null && account.getOwner().getId().equals(user.getId());
	}

	private List<AccountResponse> accountsOf(Long ownerId) {
		return accountRepository.findByOwnerIdOrderByIdAsc(ownerId).stream().map(AccountResponse::from).toList();
	}

}
