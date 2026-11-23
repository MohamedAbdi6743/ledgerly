package com.ledgerly.entity;

import java.math.BigDecimal;
import java.time.Instant;

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
 * One business event: a deposit, withdrawal, transfer or reversal. Its money effect is the set
 * of ledger entries that point at it, which always add up to zero.
 */
@Entity
@Table(name = "journal_transaction")
public class JournalTransaction {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, unique = true)
	private String reference;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private TransactionType type;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private TransactionStatus status;

	@Column(nullable = false, precision = 19, scale = 2)
	private BigDecimal amount;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "from_account_id")
	private Account fromAccount;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "to_account_id")
	private Account toAccount;

	private String description;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "created_by")
	private AppUser createdBy;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "approved_by")
	private AppUser approvedBy;

	private String decisionReason;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "reverses_id")
	private JournalTransaction reverses;

	@Column(nullable = false)
	private String idempotencyKey;

	@Column(nullable = false)
	private String requestFingerprint;

	@Column(nullable = false, updatable = false)
	private Instant createdAt;

	private Instant postedAt;

	protected JournalTransaction() {
	}

	/** A transaction that takes effect immediately (deposits, withdrawals, small transfers). */
	public static JournalTransaction posted(String reference, TransactionType type, BigDecimal amount,
			Account fromAccount, Account toAccount, String description, AppUser createdBy,
			String idempotencyKey, String requestFingerprint, Instant now) {
		JournalTransaction transaction = new JournalTransaction();
		transaction.reference = reference;
		transaction.type = type;
		transaction.status = TransactionStatus.POSTED;
		transaction.amount = amount;
		transaction.fromAccount = fromAccount;
		transaction.toAccount = toAccount;
		transaction.description = description;
		transaction.createdBy = createdBy;
		transaction.idempotencyKey = idempotencyKey;
		transaction.requestFingerprint = requestFingerprint;
		transaction.createdAt = now;
		transaction.postedAt = now;
		return transaction;
	}

	public Long getId() {
		return id;
	}

	public String getReference() {
		return reference;
	}

	public TransactionType getType() {
		return type;
	}

	public TransactionStatus getStatus() {
		return status;
	}

	public BigDecimal getAmount() {
		return amount;
	}

	public Account getFromAccount() {
		return fromAccount;
	}

	public Account getToAccount() {
		return toAccount;
	}

	public String getDescription() {
		return description;
	}

	public AppUser getCreatedBy() {
		return createdBy;
	}

	public AppUser getApprovedBy() {
		return approvedBy;
	}

	public String getDecisionReason() {
		return decisionReason;
	}

	public JournalTransaction getReverses() {
		return reverses;
	}

	public String getIdempotencyKey() {
		return idempotencyKey;
	}

	public String getRequestFingerprint() {
		return requestFingerprint;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getPostedAt() {
		return postedAt;
	}

}
