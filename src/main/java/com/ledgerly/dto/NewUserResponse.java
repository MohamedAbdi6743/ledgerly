package com.ledgerly.dto;

import com.ledgerly.entity.Role;

/**
 * Returned once, when staff create an account. The temporary password is shown only here
 * (it's stored as a BCrypt hash), so staff can hand it to the person. It must be changed at first login.
 */
public record NewUserResponse(Long id, String fullName, String email, Role role, String memberNumber, String temporaryPassword) {
}
