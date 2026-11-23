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
import com.ledgerly.dto.TransferRequest;
import com.ledgerly.entity.AccountType;
import com.ledgerly.exception.BusinessRuleException;
import com.ledgerly.repository.AccountRepository;
import com.ledgerly.repository.LedgerEntryRepository;

/**
 * Transfers racing each other on real threads and a real PostgreSQL (not @Transactional, so
 * each call commits like a real request). Fresh members and accounts per test.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class TransferConcurrencyTest {

	private static final String MANAGER = "manager@ledgerly.test";
	private static final int THREADS = 20;

	@Autowired
	private UserService userService;

	@Autowired
	private AccountService accountService;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private TransferService transferService;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private LedgerEntryRepository ledgerEntryRepository;

	private String samEmail;
	private String priyaEmail;
	private long samId;
	private long priyaId;

	@BeforeEach
	void createTwoMembers() {
		samEmail = "sam-" + UUID.randomUUID() + "@example.com";
		priyaEmail = "priya-" + UUID.randomUUID() + "@example.com";
		samId = userService.createMember("Sam Ortiz", samEmail, MANAGER).id();
		priyaId = userService.createMember("Priya Shah", priyaEmail, MANAGER).id();
	}

	/**
	 * The classic deadlock: Sam sends to Priya while Priya sends to Sam. If each request locked
	 * its own "from" account first, each would end up waiting for the other's, forever (PostgreSQL
	 * would notice and kill one). Locking lowest id first means they queue instead.
	 */
	@Test
	void transfersInOppositeDirectionsAtTheSameTimeDoNotDeadlock() throws Exception {
		String samAccount = openWith(samId, "1000.00");
		String priyaAccount = openWith(priyaId, "1000.00");

		List<Callable<Boolean>> transfers = new ArrayList<>();
		for (int i = 0; i < THREADS; i++) {
			boolean samSends = (i % 2 == 0);
			transfers.add(() -> {
				transferService.transfer(
						new TransferRequest(samSends ? samAccount : priyaAccount, samSends ? priyaAccount : samAccount,
								new BigDecimal("10.00"), null),
						newKey(), samSends ? samEmail : priyaEmail);
				return true;
			});
		}

		List<Boolean> results = runAllAtOnce(transfers); // a deadlock would surface here as an exception

		assertThat(results).hasSize(THREADS).containsOnly(true);
		// Ten $10 each way: both end where they started.
		assertThat(balanceOf(samAccount)).isEqualByComparingTo("1000.00");
		assertThat(balanceOf(priyaAccount)).isEqualByComparingTo("1000.00");
		assertThat(ledgerEntryRepository.sumOfAllEntries()).isEqualByComparingTo("0.00");
	}

	/**
	 * Sam sends $1,000 ten times at once, from two different accounts to two different accounts,
	 * so the requests share no account lock at all. Only the lock on Sam himself makes them take
	 * turns. Without it, several would read "$0 sent today" at the same moment and all go through.
	 */
	@Test
	void theDailyLimitHoldsWhenOneMemberSendsManyTransfersAtOnce() throws Exception {
		String samChequing = openWith(samId, "5000.00");
		String samSavings = openWith(samId, "5000.00");
		String priyaChequing = openWith(priyaId, null);
		String priyaSavings = openWith(priyaId, null);

		List<Callable<Boolean>> transfers = new ArrayList<>();
		for (int i = 0; i < 10; i++) {
			boolean first = (i % 2 == 0);
			transfers.add(() -> {
				try {
					transferService.transfer(new TransferRequest(first ? samChequing : samSavings,
							first ? priyaChequing : priyaSavings, new BigDecimal("1000.00"), null), newKey(), samEmail);
					return true;
				} catch (BusinessRuleException overTheLimit) {
					return false;
				}
			});
		}

		List<Boolean> results = runAllAtOnce(transfers);

		assertThat(results).filteredOn(sent -> sent).hasSize(3);   // $3,000 limit
		assertThat(balanceOf(priyaChequing).add(balanceOf(priyaSavings))).isEqualByComparingTo("3000.00");
		assertThat(ledgerEntryRepository.sumOfAllEntries()).isEqualByComparingTo("0.00");
	}

	/** Opens a chequing account and, if given an amount, deposits it as cash. */
	private String openWith(long memberId, String amount) {
		String number = accountService.openAccount(memberId, AccountType.CHEQUING, "Test", MANAGER).accountNumber();
		if (amount != null) {
			paymentService.deposit(new CashRequest(number, new BigDecimal(amount), null), newKey(), MANAGER);
		}
		return number;
	}

	private BigDecimal balanceOf(String accountNumber) {
		return accountRepository.findByAccountNumber(accountNumber).orElseThrow().getBalance();
	}

	private static String newKey() {
		return UUID.randomUUID().toString();
	}

}
