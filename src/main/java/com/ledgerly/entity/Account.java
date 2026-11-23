package com.ledgerly.entity;

import java.math.BigDecimal;
import java.time.Instant;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A chequing or savings account, or a SYSTEM account such as "Cash on hand".
 * The balance is a cached copy of the sum of this account's ledger entries. Only
 * LedgerService changes it, in the same database transaction that writes the entries.
 */
@Entity
@Table(name = "account")
public class Account {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private String accountNumber;

	/** Null for SYSTEM accounts. */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "owner_id")
	private AppUser owner;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private AccountType type;

	@Column(nullable = false)
	private String name;

	// The column is CHAR(3); telling Hibernate so keeps schema validation happy.
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(nullable = false, length = 3)
	private String currency;

	@Column(nullable = false, precision = 19, scale = 2)
	private BigDecimal balance;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private AccountStatus status;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	protected Account() {
	}

	public Account(String accountNumber, AppUser owner, AccountType type, String name, Instant createdAt) {
		this.accountNumber = accountNumber;
		this.owner = owner;
		this.type = type;
		this.name = name;
		this.currency = "CAD";
		this.balance = BigDecimal.ZERO.setScale(2);
		this.status = AccountStatus.OPEN;
		this.createdAt = createdAt;
	}

	public boolean isSystem() {
		return type == AccountType.SYSTEM;
	}

	public boolean isOpen() {
		return status == AccountStatus.OPEN;
	}

	/** Only LedgerService calls this, after checking the whole transaction balances. Returns the new balance. */
	public BigDecimal applyEntry(BigDecimal amount) {
		this.balance = this.balance.add(amount);
		return this.balance;
	}

	public void changeStatus(AccountStatus newStatus) {
		this.status = newStatus;
	}

	public Long getId() {
		return id;
	}

	public String getAccountNumber() {
		return accountNumber;
	}

	public AppUser getOwner() {
		return owner;
	}

	public AccountType getType() {
		return type;
	}

	public String getName() {
		return name;
	}

	public String getCurrency() {
		return currency;
	}

	public BigDecimal getBalance() {
		return balance;
	}

	public AccountStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

}
