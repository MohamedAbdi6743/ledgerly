package com.ledgerly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves the database itself refuses data that would break the money rules.
 * These constraints are the last line of defense if the Java code ever has a bug.
 * Each test also checks the name of the constraint that refused the data, so a test can't
 * pass because a different rule happened to fail.
 * @Transactional rolls back each test, so tests never see each other's data.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class LedgerSchemaTest {

	@Autowired
	private JdbcTemplate jdbc;

	private Long teller;
	private Long manager;
	private Long sam;
	private Long samChequing;
	private Long samSavings;

	@BeforeEach
	void createPeopleAndAccounts() {
		teller = insertUser("dana@ledgerly.test", "TELLER", null);
		manager = insertUser("omar@ledgerly.test", "MANAGER", null);
		// Not "M000001": the app hands out M-numbers from a sequence, and tests that commit
		// (like the concurrency test) may already have used the first ones.
		sam = insertUser("sam@example.com", "MEMBER", "TEST-0001");
		samChequing = insertAccount("100200300401", sam, "CHEQUING");
		samSavings = insertAccount("100200300402", sam, "SAVINGS");
	}

	// ---------- setup ----------

	@Test
	void migrationCreatesTablesAndTheCashOnHandAccount() {
		List<String> tables = jdbc.queryForList(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
		String cashAccount = jdbc.queryForObject(
				"SELECT name FROM account WHERE account_number = '000000000001' AND type = 'SYSTEM'", String.class);

		assertThat(tables).contains("app_user", "account", "journal_transaction", "ledger_entry", "audit_event");
		assertThat(cashAccount).isEqualTo("Cash on hand");
	}

	// ---------- accounts ----------

	@Test
	void memberAccountCannotGoBelowZero() {
		assertThatThrownBy(() -> jdbc.update("UPDATE account SET balance = -0.01 WHERE id = ?", samChequing))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("account_no_overdraft");
	}

	@Test
	void systemAccountMayBeNegative() {
		// Cash on hand goes negative as members deposit cash: the money came from outside.
		int updated = jdbc.update("UPDATE account SET balance = -250.00 WHERE account_number = '000000000001'");

		assertThat(updated).isEqualTo(1);
	}

	@Test
	void memberAccountMustHaveAnOwner() {
		assertThatThrownBy(() -> insertAccount("100200300499", null, "CHEQUING"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("account_owner_check");
	}

	@Test
	void systemAccountCannotHaveAnOwner() {
		assertThatThrownBy(() -> insertAccount("000000000002", sam, "SYSTEM"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("account_owner_check");
	}

	@Test
	void onlyCanadianDollarsAreAllowed() {
		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO account (account_number, owner_id, type, name, currency) VALUES ('100200300498', ?, 'CHEQUING', 'USD account', 'USD')",
				sam))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("account_currency_check");
	}

	// ---------- users ----------

	@Test
	void staffCannotHaveAMemberNumber() {
		assertThatThrownBy(() -> insertUser("lee@ledgerly.test", "TELLER", "TEST-0002"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("app_user_member_number");
	}

	@Test
	void membersMustHaveAMemberNumber() {
		assertThatThrownBy(() -> insertUser("priya@example.com", "MEMBER", null))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("app_user_member_number");
	}

	// ---------- journal transactions ----------

	@Test
	void transactionAmountMustBePositive() {
		assertThatThrownBy(() -> insertTransfer("TX-1", "0.00", sam, "key-1"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("journal_amount_positive");
	}

	@Test
	void nobodyCanApproveTheirOwnRequest() {
		Long transfer = insertTransfer("TX-1", "6000.00", manager, "key-1");

		assertThatThrownBy(() -> jdbc.update(
				"UPDATE journal_transaction SET approved_by = ?, status = 'POSTED' WHERE id = ?", manager, transfer))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("journal_four_eyes");
	}

	@Test
	void sameUserCannotReuseAnIdempotencyKey() {
		insertTransfer("TX-1", "50.00", sam, "key-1");

		assertThatThrownBy(() -> insertTransfer("TX-2", "50.00", sam, "key-1"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("journal_idempotency");
	}

	@Test
	void differentUsersMayUseTheSameKeyValue() {
		insertTransfer("TX-1", "50.00", sam, "key-1");

		Long tellersTransfer = insertTransfer("TX-2", "50.00", teller, "key-1");

		assertThat(tellersTransfer).isNotNull();
	}

	@Test
	void aTransactionCanOnlyBeReversedOnce() {
		Long original = insertTransfer("TX-1", "50.00", sam, "key-1");
		insertReversal("TX-2", original, "key-2");

		assertThatThrownBy(() -> insertReversal("TX-3", original, "key-3"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("journal_transaction_reverses_id_key");
	}

	@Test
	void aReversalMustSayWhatItReverses() {
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO journal_transaction (reference, type, status, amount, created_by, idempotency_key, request_fingerprint)
				VALUES ('TX-1', 'REVERSAL', 'POSTED', 50.00, ?, 'key-1', 'fp')""", manager))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("journal_reversal_link");
	}

	@Test
	void transferMustBeBetweenTwoDifferentAccounts() {
		assertThatThrownBy(() -> jdbc.update("""
				INSERT INTO journal_transaction (reference, type, status, amount, from_account_id, to_account_id,
				                                 created_by, idempotency_key, request_fingerprint)
				VALUES ('TX-1', 'TRANSFER', 'POSTED', 50.00, ?, ?, ?, 'key-1', 'fp')""",
				samChequing, samChequing, sam))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("journal_different_accounts");
	}

	// ---------- ledger entries and money precision ----------

	@Test
	void ledgerEntryCannotBeZero() {
		Long transfer = insertTransfer("TX-1", "50.00", sam, "key-1");

		assertThatThrownBy(() -> insertEntry(transfer, samChequing, "0.00"))
				.isInstanceOf(DataIntegrityViolationException.class)
				.hasMessageContaining("ledger_entry_nonzero");
	}

	@Test
	void numericAmountsAddUpExactly() {
		// With floating point, 0.10 + 0.20 = 0.30000000000000004. NUMERIC is exact.
		Long transfer = insertTransfer("TX-1", "0.30", sam, "key-1");
		insertEntry(transfer, samChequing, "0.10");
		insertEntry(transfer, samChequing, "0.20");
		insertEntry(transfer, samSavings, "-0.30");

		BigDecimal total = jdbc.queryForObject(
				"SELECT SUM(amount) FROM ledger_entry WHERE transaction_id = ?", BigDecimal.class, transfer);
		BigDecimal chequing = jdbc.queryForObject(
				"SELECT SUM(amount) FROM ledger_entry WHERE account_id = ?", BigDecimal.class, samChequing);

		assertThat(total).isEqualByComparingTo("0.00");
		assertThat(chequing).isEqualByComparingTo("0.30");
	}

	@Test
	void postgresQuietlyRoundsAThirdDecimalPlace() {
		// NUMERIC(19,2) stores 10.005 as 10.01 without any error. That's why the Java code
		// rejects amounts with more than two decimal places before they reach the database.
		Long transfer = insertTransfer("TX-1", "10.005", sam, "key-1");

		BigDecimal stored = jdbc.queryForObject("SELECT amount FROM journal_transaction WHERE id = ?", BigDecimal.class, transfer);

		assertThat(stored).isEqualByComparingTo("10.01");
	}

	// ---------- helpers ----------

	private Long insertUser(String email, String role, String memberNumber) {
		return jdbc.queryForObject(
				"INSERT INTO app_user (email, password_hash, full_name, role, member_number) VALUES (?, 'not-a-real-hash', 'Test Person', ?, ?) RETURNING id",
				Long.class, email, role, memberNumber);
	}

	private Long insertAccount(String number, Long owner, String type) {
		return jdbc.queryForObject(
				"INSERT INTO account (account_number, owner_id, type, name) VALUES (?, ?, ?, 'Test account') RETURNING id",
				Long.class, number, owner, type);
	}

	private Long insertTransfer(String reference, String amount, Long createdBy, String key) {
		return jdbc.queryForObject("""
				INSERT INTO journal_transaction (reference, type, status, amount, from_account_id, to_account_id,
				                                 created_by, idempotency_key, request_fingerprint)
				VALUES (?, 'TRANSFER', 'POSTED', ?, ?, ?, ?, ?, 'fp') RETURNING id""",
				Long.class, reference, new BigDecimal(amount), samChequing, samSavings, createdBy, key);
	}

	private Long insertReversal(String reference, Long original, String key) {
		return jdbc.queryForObject("""
				INSERT INTO journal_transaction (reference, type, status, amount, created_by, reverses_id,
				                                 idempotency_key, request_fingerprint)
				VALUES (?, 'REVERSAL', 'POSTED', 50.00, ?, ?, ?, 'fp') RETURNING id""",
				Long.class, reference, manager, original, key);
	}

	private void insertEntry(Long transaction, Long account, String amount) {
		jdbc.update("INSERT INTO ledger_entry (transaction_id, account_id, amount, balance_after) VALUES (?, ?, ?, 0)",
				transaction, account, new BigDecimal(amount));
	}

}
