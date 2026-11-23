package com.ledgerly.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.ledgerly.dto.CreateMemberRequest;
import com.ledgerly.dto.NewUserResponse;
import com.ledgerly.dto.UserResponse;
import com.ledgerly.service.UserService;

import jakarta.validation.Valid;

/** Member management for tellers and managers (see SecurityConfig). */
@RestController
@RequestMapping("/api/members")
public class MemberController {

	private final UserService userService;

	public MemberController(UserService userService) {
		this.userService = userService;
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public NewUserResponse createMember(@Valid @RequestBody CreateMemberRequest request, Authentication authentication) {
		return userService.createMember(request.fullName(), request.email(), authentication.getName());
	}

	@GetMapping
	public List<UserResponse> searchMembers(@RequestParam(defaultValue = "") String query) {
		return userService.searchMembers(query).stream().map(UserResponse::from).toList();
	}

	@GetMapping("/{memberId}")
	public UserResponse getMember(@PathVariable Long memberId) {
		return UserResponse.from(userService.getMember(memberId));
	}

}
