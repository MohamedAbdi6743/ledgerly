package com.ledgerly.service;

import java.math.BigDecimal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.ledgerly.entity.Account;

/**
 * Which requests need a second person (maker-checker): money of $5,000 or more leaving a member.
 *
 *  - Cash withdrawals of $5,000+.
 *  - Transfers of $5,000+ between accounts with different owners.
 *
 * Moving your own money between your own accounts doesn't need approval: nobody else's money
 * is at risk. Deposits don't either (money coming in).
 */
@Component
public class ApprovalPolicy {

	private final BigDecimal threshold;

	public ApprovalPolicy(@Value("${ledgerly.approvals.threshold}") BigDecimal threshold) {
		this.threshold = threshold;
	}

	public boolean withdrawalNeedsApproval(BigDecimal amount) {
		return amount.compareTo(threshold) >= 0;
	}

	public boolean transferNeedsApproval(BigDecimal amount, Account from, Account to) {
		return amount.compareTo(threshold) >= 0 && !sameOwner(from, to);
	}

	private static boolean sameOwner(Account from, Account to) {
		return from.getOwner() != null && to.getOwner() != null
				&& from.getOwner().getId().equals(to.getOwner().getId());
	}

}
