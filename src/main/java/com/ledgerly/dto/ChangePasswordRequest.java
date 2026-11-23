package com.ledgerly.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** JSON body for POST /api/auth/password. */
public record ChangePasswordRequest(

		@NotBlank(message = "Current password is required")
		String currentPassword,

		// BCrypt only uses the first 72 bytes, so longer passwords are refused rather than silently cut.
		@NotBlank(message = "New password is required")
		@Size(min = 12, max = 72, message = "New password must be between 12 and 72 characters")
		String newPassword) {
}
