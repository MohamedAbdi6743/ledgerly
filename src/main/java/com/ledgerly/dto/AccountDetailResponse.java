package com.ledgerly.dto;

import java.util.List;

/** An account plus its most recent activity, newest first. */
public record AccountDetailResponse(AccountResponse account, List<ActivityLine> recentActivity) {
}
