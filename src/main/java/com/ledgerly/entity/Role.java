package com.ledgerly.entity;

/** What a user may do. Stored as text in app_user.role. */
public enum Role {
	MEMBER,
	TELLER,
	MANAGER;

	public boolean isStaff() {
		return this == TELLER || this == MANAGER;
	}
}
