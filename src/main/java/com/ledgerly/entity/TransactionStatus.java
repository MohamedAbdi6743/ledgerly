package com.ledgerly.entity;

/** POSTED transactions have ledger entries. PENDING_APPROVAL and REJECTED ones don't. */
public enum TransactionStatus {
	POSTED,
	PENDING_APPROVAL,
	REJECTED
}
