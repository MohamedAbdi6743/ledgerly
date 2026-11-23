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

	/** A transaction that takes effect immediately. */
	public static JournalTransaction posted(String reference, TransactionType type, BigDecimal amount,
			Account fromAccount, Account toAccount, String description, AppUser createdBy,
			String idempotencyKey, String requestFingerprint, Instant now) {
		JournalTransaction transaction = create(reference, type, amount, fromAccount, toAccount, description,
				createdBy, idempotencyKey, requestFingerprint, now);
		transaction.status = TransactionStatus.POSTED;
		transaction.postedAt = now;
		return transaction;
	}

	/** A large request that waits for a manager. It has no ledger entries until it's approved. */
	public static JournalTransaction pending(String reference, TransactionType type, BigDecimal amount,
			Account fromAccount, Account toAccount, String description, AppUser createdBy,
			String idempotencyKey, String requestFingerprint, Instant now) {
		JournalTransaction transaction = create(reference, type, amount, fromAccount, toAccount, description,
				createdBy, idempotencyKey, requestFingerprint, now);
		transaction.status = TransactionStatus.PENDING_APPROVAL;
		return transaction;
	}

	/**
	 * Undoes a posted transaction. The money goes back the way it came, so "from" and "to" swap.
	 * The reason goes in the description, where statements will show it.
	 */
	public static JournalTransaction reversalOf(JournalTransaction original, String reference, String reason,
			AppUser manager, String idempotencyKey, String requestFingerprint, Instant now) {
		JournalTransaction reversal = posted(reference, TransactionType.REVERSAL, original.amount,
				original.toAccount, original.fromAccount, reason, manager, idempotencyKey, requestFingerprint, now);
		reversal.reverses = original;
		return reversal;
	}

	private static JournalTransaction create(String reference, TransactionType type, BigDecimal amount,
			Account fromAccount, Account toAccount, String description, AppUser createdBy,
			String idempotencyKey, String requestFingerprint, Instant now) {
		JournalTransaction transaction = new JournalTransaction();
		transaction.reference = reference;
		transaction.type = type;
		transaction.amount = amount;
		transaction.fromAccount = fromAccount;
		transaction.toAccount = toAccount;
		transaction.description = description;
		transaction.createdBy = createdBy;
		transaction.idempotencyKey = idempotencyKey;
		transaction.requestFingerprint = requestFingerprint;
		transaction.createdAt = now;
		return transaction;
	}

	public boolean isPending() {
		return status == TransactionStatus.PENDING_APPROVAL;
	}

	/** ApprovalService checks the four-eyes rule first; the database checks it again. */
	public void approve(AppUser manager, Instant now) {
		requirePending();
		this.status = TransactionStatus.POSTED;
		this.approvedBy = manager;
		this.postedAt = now;
	}

	/** approved_by records whoever decided, approve or reject. */
	public void reject(AppUser manager, String reason) {
		requirePending();
		this.status = TransactionStatus.REJECTED;
		this.approvedBy = manager;
		this.decisionReason = reason;
	}

	private void requirePending() {
		if (!isPending()) {
			throw new IllegalStateException("Transaction " + reference + " is " + status + ", not pending.");
		}
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
