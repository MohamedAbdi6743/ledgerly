package com.ledgerly.dto;

/** One item in a manager's approval queue: the request, and who made it. */
public record PendingRequestResponse(TransactionResponse transaction, String requestedBy, String requestedByEmail) {
}
