package com.ledgerly.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ledgerly.dto.ChangePasswordRequest;
import com.ledgerly.dto.UserResponse;
import com.ledgerly.service.UserService;

import jakarta.validation.Valid;

/** Account-level endpoints. Login and logout themselves are handled by Spring Security (SecurityConfig). */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final UserService userService;

	public AuthController(UserService userService) {
		this.userService = userService;
	}

	@GetMapping("/me")
	public UserResponse currentUser(Authentication authentication) {
		return UserResponse.from(userService.getByEmail(authentication.getName()));
	}

	@PostMapping("/password")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void changePassword(@Valid @RequestBody ChangePasswordRequest request, Authentication authentication) {
		userService.changePassword(authentication.getName(), request.currentPassword(), request.newPassword());
	}

	/** Called once when a page loads; reading the token makes Spring Security send the XSRF-TOKEN cookie. */
	@GetMapping("/csrf")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void csrfToken(CsrfToken csrfToken) {
		csrfToken.getToken();
	}

}
