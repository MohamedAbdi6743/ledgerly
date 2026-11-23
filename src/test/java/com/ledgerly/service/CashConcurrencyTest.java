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
import com.ledgerly.exception.BusinessRuleException;
import com.ledgerly.repository.AccountRepository;
import com.ledgerly.repository.JournalTransactionRepository;
import com.ledgerly.repository.LedgerEntryRepository;

/**
 * Many requests hitting the same account at the same moment, on real threads and a real
 * PostgreSQL. Not @Transactional: each call must commit on its own, like real requests do.
 * Each test uses a fresh member and account, so leftover rows don't affect other tests.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CashConcurrencyTest {

	private static final String MANAGER = "manager@ledgerly.test";
	private static final int THREADS = 20;

	@Autowired
	private UserService userService;

	@Autowired
	private AccountService accountService;

	@Autowired
	private PaymentService paymentService;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private JournalTransactionRepository journalTransactionRepository;

	@Autowired
	private LedgerEntryRepository ledgerEntryRepository;

	private String accountNumber;

	@BeforeEach
	void openAccountWithOneHundredDollars() {
		String email = "race-" + UUID.randomUUID() + "@example.com";
		long memberId = userService.createMember("Race Test", email, MANAGER).id();
		accountNumber = accountService.openAccount(memberId, AccountType.CHEQUING, "Race", MANAGER).accountNumber();
		paymentService.deposit(new CashRequest(accountNumber, new BigDecimal("100.00"), null), newKey(), MANAGER);
	}

	@Test
	void twentySimultaneousTenDollarWithdrawalsFromOneHundredDollars() throws Exception {
		List<Callable<Boolean>> withdrawals = new ArrayList<>();
		for (int i = 0; i < THREADS; i++) {
			withdrawals.add(() -> {
				try {
					paymentService.withdraw(new CashRequest(accountNumber, new BigDecimal("10.00"), null), newKey(), MANAGER);
					return true;
				} catch (BusinessRuleException insufficientFunds) {
					return false;
				}
			});
		}

		List<Boolean> results = runAllAtOnce(withdrawals);

		// Without the row lock, several threads would read $100 at once and the balance would
		// go negative (or the database CHECK would reject some with a confusing error).
		assertThat(results).filteredOn(succeeded -> succeeded).hasSize(10);
		assertThat(results).filteredOn(succeeded -> !succeeded).hasSize(10);

		Long accountId = accountRepository.findIdByAccountNumber(accountNumber).orElseThrow();
		assertThat(accountRepository.findById(accountId).orElseThrow().getBalance()).isEqualByComparingTo("0.00");
		assertThat(ledgerEntryRepository.sumForAccount(accountId)).isEqualByComparingTo("0.00");
		assertThat(ledgerEntryRepository.sumOfAllEntries()).isEqualByComparingTo("0.00");
	}

	@Test
	void twentySimultaneousRetriesOfOneRequestPostItOnce() throws Exception {
		String key = newKey();
		List<Callable<Boolean>> retries = new ArrayList<>();
		for (int i = 0; i < THREADS; i++) {
			retries.add(() -> paymentService
					.withdraw(new CashRequest(accountNumber, new BigDecimal("25.00"), null), key, MANAGER)
					.replayed());
		}

		List<Boolean> replayed = runAllAtOnce(retries);

		assertThat(replayed).filteredOn(wasReplay -> !wasReplay).hasSize(1);
		assertThat(replayed).filteredOn(wasReplay -> wasReplay).hasSize(THREADS - 1);

		Long accountId = accountRepository.findIdByAccountNumber(accountNumber).orElseThrow();
		assertThat(accountRepository.findById(accountId).orElseThrow().getBalance()).isEqualByComparingTo("75.00");
		assertThat(ledgerEntryRepository.sumOfAllEntries()).isEqualByComparingTo("0.00");
	}

	private static String newKey() {
		return UUID.randomUUID().toString();
	}

}
