package com.ledgerly.dto;

import com.ledgerly.entity.Role;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** JSON body for POST /api/staff. Role must be TELLER or MANAGER. */
public record CreateStaffRequest(

		@NotBlank(message = "Full name is required")
		@Size(max = 100, message = "Full name must be at most 100 characters")
		String fullName,

		@NotBlank(message = "Email is required")
		@Email(message = "Email must be a valid email address")
		@Size(max = 255, message = "Email must be at most 255 characters")
		String email,

		@NotNull(message = "Role is required")
		Role role) {
}
