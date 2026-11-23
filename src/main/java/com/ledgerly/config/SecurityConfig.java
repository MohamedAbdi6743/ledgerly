package com.ledgerly.config;

import java.io.IOException;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CsrfException;

import com.ledgerly.dto.UserResponse;
import com.ledgerly.exception.ApiError;
import com.ledgerly.service.UserService;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Who may call which URL, how login works, CSRF protection and security headers.
 * Session login: after logging in, the browser holds an HttpOnly session cookie.
 * Finer rules ("a member may only see their own accounts") are checked in the services,
 * because a URL rule can't know who owns a record.
 */
@Configuration
public class SecurityConfig {

	private final ObjectMapper objectMapper;

	public SecurityConfig(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	// UserService is a method parameter (not a constructor parameter) to avoid a circular
	// dependency: UserService needs the PasswordEncoder created by this class.
	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, UserService userService) throws Exception {
		http
			.authorizeHttpRequests(auth -> auth
				.requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
				// Rules are checked top to bottom; the first match wins.
				.requestMatchers("/api/staff/**").hasRole("MANAGER")
				.requestMatchers("/api/members/**").hasAnyRole("TELLER", "MANAGER")
				.requestMatchers("/api/me/**").hasRole("MEMBER")
				.requestMatchers(HttpMethod.PUT, "/api/accounts/*/status").hasRole("MANAGER")
				.requestMatchers("/api/deposits", "/api/withdrawals").hasAnyRole("TELLER", "MANAGER")
				// Accounts and transactions: any logged-in user; the services check ownership.
				.requestMatchers("/api/**").authenticated()
				// Pages, CSS and JS are public; the data they show comes from /api.
				.anyRequest().permitAll())

			// CSRF token in an XSRF-TOKEN cookie, copied by our JavaScript into an X-XSRF-TOKEN header.
			.csrf(csrf -> csrf.spa())

			// Only scripts and styles served by this site may run (second line of defense against XSS).
			.headers(headers -> headers.contentSecurityPolicy(csp -> csp.policyDirectives(
				"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
					+ "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'")))

			.formLogin(form -> form
				.loginProcessingUrl("/api/auth/login")
				.usernameParameter("email")
				.passwordParameter("password")
				.successHandler((request, response, authentication) ->
					writeJson(response, HttpStatus.OK, UserResponse.from(userService.getByEmail(authentication.getName()))))
				.failureHandler((request, response, exception) ->
					writeJson(response, HttpStatus.UNAUTHORIZED,
						ApiError.of(HttpStatus.UNAUTHORIZED, "Email or password is incorrect."))))

			.logout(logout -> logout
				.logoutUrl("/api/auth/logout")
				.logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))

			.exceptionHandling(errors -> errors
				.authenticationEntryPoint((request, response, exception) ->
					writeJson(response, HttpStatus.UNAUTHORIZED, ApiError.of(HttpStatus.UNAUTHORIZED, "Please log in first.")))
				.accessDeniedHandler((request, response, exception) -> {
					String message = (exception instanceof CsrfException)
						? "Missing or invalid CSRF token. Refresh the page and try again."
						: "You don't have permission to do this.";
					writeJson(response, HttpStatus.FORBIDDEN, ApiError.of(HttpStatus.FORBIDDEN, message));
				}));

		return http.build();
	}

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	private void writeJson(HttpServletResponse response, HttpStatus status, Object body) throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		objectMapper.writeValue(response.getOutputStream(), body);
	}

}
