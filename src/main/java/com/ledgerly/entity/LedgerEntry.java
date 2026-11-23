package com.ledgerly.entity;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One line of the ledger: money into (positive) or out of (negative) one account.
 * Append-only: no setters, never updated or deleted.
 */
@Entity
@Table(name = "ledger_entry")
public class LedgerEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "transaction_id")
	private JournalTransaction transaction;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "account_id")
	private Account account;

	@Column(nullable = false, precision = 19, scale = 2)
	private BigDecimal amount;

	/** The account's balance right after this entry. Statements use it for the running balance. */
	@Column(nullable = false, precision = 19, scale = 2)
	private BigDecimal balanceAfter;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	protected LedgerEntry() {
	}

	public LedgerEntry(JournalTransaction transaction, Account account, BigDecimal amount, BigDecimal balanceAfter,
			Instant createdAt) {
		this.transaction = transaction;
		this.account = account;
		this.amount = amount;
		this.balanceAfter = balanceAfter;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public JournalTransaction getTransaction() {
		return transaction;
	}

	public Account getAccount() {
		return account;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public BigDecimal getBalanceAfter() {
		return balanceAfter;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
