package com.ledgerly.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ledgerly.dto.CreateStaffRequest;
import com.ledgerly.dto.NewUserResponse;
import com.ledgerly.service.UserService;

import jakarta.validation.Valid;

/** Managers create teller and manager accounts (see SecurityConfig). */
@RestController
@RequestMapping("/api/staff")
public class StaffController {

	private final UserService userService;

	public StaffController(UserService userService) {
		this.userService = userService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public NewUserResponse createStaff(@Valid @RequestBody CreateStaffRequest request, Authentication authentication) {
		return userService.createStaff(request.fullName(), request.email(), request.role(), authentication.getName());
	}

}
