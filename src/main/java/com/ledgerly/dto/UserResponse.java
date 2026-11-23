package com.ledgerly.dto;

import com.ledgerly.entity.AppUser;
import com.ledgerly.entity.Role;

/** What the API shows about a user. Never includes the password hash. */
public record UserResponse(Long id, String fullName, String email, Role role, String memberNumber, boolean mustChangePassword) {

	public static UserResponse from(AppUser user) {
		return new UserResponse(user.getId(), user.getFullName(), user.getEmail(), user.getRole(),
				user.getMemberNumber(), user.isMustChangePassword());
	}

}
