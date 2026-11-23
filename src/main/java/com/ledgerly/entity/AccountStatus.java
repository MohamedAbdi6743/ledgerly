package com.ledgerly.entity;

/** A FROZEN account can't send or receive money until a manager unfreezes it. */
public enum AccountStatus {
	OPEN,
	FROZEN
}
