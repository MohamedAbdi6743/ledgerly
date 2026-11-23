package com.ledgerly.config;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import com.ledgerly.exception.PasswordChangeRequiredException;
import com.ledgerly.service.UserService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Runs before every /api request (except /api/auth/**, see WebConfig). Someone still using a
 * temporary password gets 403 until they change it, whatever their role.
 * It reads the flag from the database each time, so it takes effect the moment the password changes.
 */
@Component
public class PasswordChangeInterceptor implements HandlerInterceptor {

	private final UserService userService;

	public PasswordChangeInterceptor(UserService userService) {
		this.userService = userService;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
			return true; // Spring Security already decided whether anonymous access is allowed.
		}
		userService.findByEmail(authentication.getName()).ifPresent(user -> {
			if (user.isMustChangePassword()) {
				throw new PasswordChangeRequiredException();
			}
		});
		return true;
	}

}
