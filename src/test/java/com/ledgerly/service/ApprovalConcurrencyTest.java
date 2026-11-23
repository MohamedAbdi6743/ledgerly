package com.ledgerly.service;

import static com.ledgerly.service.ConcurrencySupport.runAllAtOnce;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import com.ledgerly.TestcontainersConfiguration;
import com.ledgerly.dto.CashRequest;
import com.ledgerly.entity.AccountType;
import com.ledgerly.entity.Role;
import com.ledgerly.exception.BusinessRuleException;
import com.ledgerly.repository.AccountRepository;
import com.ledgerly.repository.LedgerEntryRepository;

/**
 * Two managers clicking at the same moment, on real threads and a real PostgreSQL (not
 * @Transactional, so each call commits). Fresh people and accounts per test.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class ApprovalConcurrencyTest {

	private static final String MANAGER = "manager@ledgerly.test";
	private static final int THREADS = 10;

	@Autowired
	private UserService userService;

	@Autowired
	private AccountService accountService;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private ApprovalService approvalService;

	@Autowired
	private ReversalService reversalService;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private LedgerEntryRepository ledgerEntryRepository;

	private String accountNumber;
	private String secondManager;

	@BeforeEach
	void openAccountWithTwentyThousandDollars() {
		long memberId = userService.createMember("Race Test", "race-" + UUID.randomUUID() + "@example.com", MANAGER).id();
		accountNumber = accountService.openAccount(memberId, AccountType.CHEQUING, "Race", MANAGER).accountNumber();
		paymentService.deposit(new CashRequest(accountNumber, new BigDecimal("20000.00"), null), newKey(), MANAGER);
		secondManager = "checker-" + UUID.randomUUID() + "@ledgerly.test";
		userService.createStaff("Second Manager", secondManager, Role.MANAGER, MANAGER);
	}

	@Test
	void tenSimultaneousApprovalsOfOneRequestPostItOnce() throws Exception {
		String reference = paymentService.withdraw(new CashRequest(accountNumber, new BigDecimal("6000.00"), null),
				newKey(), MANAGER).transaction().reference();

		List<Callable<Boolean>> approvals = new ArrayList<>();
		for (int i = 0; i < THREADS; i++) {
			approvals.add(() -> {
				try {
					approvalService.approve(reference, secondManager);
					return true;
				} catch (BusinessRuleException alreadyDecided) {
					return false;
				}
			});
		}

		List<Boolean> results = runAllAtOnce(approvals);

		// Without the lock on the request, several approvals would read "pending" at once and each
		// write the entries, taking $6,000 out several times.
		assertThat(results).filteredOn(approved -> approved).hasSize(1);
		assertThat(balance()).isEqualByComparingTo("14000.00");
		assertThat(ledgerEntryRepository.sumOfAllEntries()).isEqualByComparingTo("0.00");
	}

	@Test
	void tenSimultaneousReversalsOfOneTransactionUndoItOnce() throws Exception {
		String reference = paymentService.deposit(new CashRequest(accountNumber, new BigDecimal("500.00"), null),
				newKey(), MANAGER).transaction().reference();

		List<Callable<Boolean>> reversals = new ArrayList<>();
		for (int i = 0; i < THREADS; i++) {
			reversals.add(() -> {
				try {
					reversalService.reverse(reference, "Duplicate deposit", newKey(), MANAGER);
					return true;
				} catch (BusinessRuleException alreadyReversed) {
					return false;
				}
			});
		}

		List<Boolean> results = runAllAtOnce(reversals);

		assertThat(results).filteredOn(reversed -> reversed).hasSize(1);
		assertThat(balance()).isEqualByComparingTo("20000.00");
		assertThat(ledgerEntryRepository.sumOfAllEntries()).isEqualByComparingTo("0.00");
	}

	private BigDecimal balance() {
		return accountRepository.findByAccountNumber(accountNumber).orElseThrow().getBalance();
	}

	private static String newKey() {
		return UUID.randomUUID().toString();
	}

}
