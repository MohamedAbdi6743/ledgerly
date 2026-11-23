package com.ledgerly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ledgerly.entity.Account;
import com.ledgerly.entity.AccountStatus;
import com.ledgerly.entity.AccountType;
import com.ledgerly.entity.AppUser;
import com.ledgerly.entity.JournalTransaction;
import com.ledgerly.entity.LedgerEntry;
import com.ledgerly.entity.Role;
import com.ledgerly.exception.BusinessRuleException;
import com.ledgerly.repository.AccountRepository;
import com.ledgerly.repository.LedgerEntryRepository;
import com.ledgerly.service.LedgerService.Leg;

/** The ledger's own rules, with no database: balanced entries, no overdraft, frozen accounts. */
@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-22T15:00:00Z");

	@Mock
	private AccountRepository accountRepository;

	@Mock
	private LedgerEntryRepository ledgerEntryRepository;

	private LedgerService ledgerService;

	private final AppUser sam = new AppUser("sam@example.com", "hash", "Sam Ortiz", Role.MEMBER, "M000001", false, NOW);
	private Account chequing;
	private Account cash;
	private JournalTransaction transaction;

	@BeforeEach
	void setUp() {
		ledgerService = new LedgerService(accountRepository, ledgerEntryRepository, Clock.fixed(NOW, ZoneOffset.UTC));
		chequing = new Account("100200000001", sam, AccountType.CHEQUING, "Chequing", NOW);
		cash = new Account("000000000001", null, AccountType.SYSTEM, "Cash on hand", NOW);
		transaction = JournalTransaction.posted("TX-TEST0001", null, money("100.00"), null, chequing, null, null,
				"key-12345678", "fingerprint", NOW);
	}

	@Test
	void depositWritesBalancedEntriesAndUpdatesBothBalances() {
		when(ledgerEntryRepository.saveAll(anyList())).thenAnswer(call -> call.getArgument(0));

		List<LedgerEntry> entries = ledgerService.post(transaction,
				List.of(new Leg(chequing, money("100.00")), new Leg(cash, money("-100.00"))));

		assertThat(chequing.getBalance()).isEqualByComparingTo("100.00");
		assertThat(cash.getBalance()).isEqualByComparingTo("-100.00");   // the cash drawer may go negative
		assertThat(entries).extracting(LedgerEntry::getBalanceAfter)
				.usingComparatorForType(BigDecimal::compareTo, BigDecimal.class)
				.containsExactly(money("100.00"), money("-100.00"));
		assertThat(entries).allSatisfy(entry -> assertThat(entry.getCreatedAt()).isEqualTo(NOW));
	}

	@Test
	void refusesEntriesThatDoNotAddUpToZero() {
		assertThatThrownBy(() -> ledgerService.post(transaction,
				List.of(new Leg(chequing, money("100.00")), new Leg(cash, money("-99.99")))))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("add up to zero");
		assertNothingChanged();
	}

	@Test
	void refusesASingleEntry() {
		assertThatThrownBy(() -> ledgerService.post(transaction, List.of(new Leg(chequing, money("100.00")))))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("at least two");
	}

	@Test
	void refusesZeroAndFractionsOfACent() {
		assertThatThrownBy(() -> LedgerService.checkBalanced(
				List.of(new Leg(chequing, money("0.00")), new Leg(cash, money("0.00")))))
				.hasMessageContaining("can't be zero");
		assertThatThrownBy(() -> LedgerService.checkBalanced(
				List.of(new Leg(chequing, money("0.005")), new Leg(cash, money("-0.005")))))
				.hasMessageContaining("fractions of a cent");
	}

	@Test
	void refusesToTakeAMemberAccountBelowZero() {
		assertThatThrownBy(() -> ledgerService.post(transaction,
				List.of(new Leg(chequing, money("-0.01")), new Leg(cash, money("0.01")))))
				.isInstanceOf(BusinessRuleException.class)
				.hasMessageContaining("Insufficient funds");
		assertNothingChanged();
	}

	@Test
	void refusesMoneyMovingThroughAFrozenAccount() {
		chequing.changeStatus(AccountStatus.FROZEN);

		assertThatThrownBy(() -> ledgerService.post(transaction,
				List.of(new Leg(chequing, money("50.00")), new Leg(cash, money("-50.00")))))
				.isInstanceOf(BusinessRuleException.class)
				.hasMessageContaining("frozen");
		assertNothingChanged();
	}

	private void assertNothingChanged() {
		assertThat(chequing.getBalance()).isEqualByComparingTo("0.00");
		assertThat(cash.getBalance()).isEqualByComparingTo("0.00");
		verify(ledgerEntryRepository, never()).saveAll(anyList());
	}

	private static BigDecimal money(String amount) {
		return new BigDecimal(amount);
	}

}
