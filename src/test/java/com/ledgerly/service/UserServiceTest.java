package com.ledgerly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import com.ledgerly.dto.NewUserResponse;
import com.ledgerly.entity.AppUser;
import com.ledgerly.entity.Role;
import com.ledgerly.exception.BadRequestException;
import com.ledgerly.exception.BusinessRuleException;
import com.ledgerly.repository.UserRepository;

/** Unit tests: the repository and audit log are Mockito fakes, so only UserService's own rules run. */
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-22T15:00:00Z");

	@Mock
	private UserRepository userRepository;

	@Mock
	private AuditService auditService;

	private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
	private final AppUser teller = new AppUser("dana@ledgerly.test", "hash", "Dana Lee", Role.TELLER, null, false, NOW);

	private UserService userService;

	@BeforeEach
	void setUp() {
		userService = new UserService(userRepository, passwordEncoder, new TemporaryPasswordGenerator(),
				auditService, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void createMemberGivesMemberNumberAndHashedTemporaryPassword() {
		when(userRepository.findByEmail("dana@ledgerly.test")).thenReturn(Optional.of(teller));
		when(userRepository.nextMemberNumber()).thenReturn(42L);
		when(userRepository.existsByEmail("sam@example.com")).thenReturn(false);
		// Like the database, the fake repository gives the saved user an id.
		when(userRepository.save(any(AppUser.class))).thenAnswer(call -> withId(call.getArgument(0), 100L));

		NewUserResponse created = userService.createMember(" Sam Ortiz ", " Sam@Example.com ", "dana@ledgerly.test");

		assertThat(created.memberNumber()).isEqualTo("M000042");
		assertThat(created.email()).isEqualTo("sam@example.com");
		assertThat(created.fullName()).isEqualTo("Sam Ortiz");
		verify(userRepository).save(org.mockito.ArgumentMatchers.argThat(saved ->
				saved.isMustChangePassword()
						&& !saved.getPasswordHash().equals(created.temporaryPassword())
						&& passwordEncoder.matches(created.temporaryPassword(), saved.getPasswordHash())));
		verify(auditService).record(eq(teller), eq("MEMBER_CREATED"), eq("USER"), any(), any());
	}

	@Test
	void duplicateEmailIsRefusedAndNothingIsSaved() {
		when(userRepository.findByEmail("dana@ledgerly.test")).thenReturn(Optional.of(teller));
		when(userRepository.nextMemberNumber()).thenReturn(43L);
		when(userRepository.existsByEmail("sam@example.com")).thenReturn(true);

		assertThatThrownBy(() -> userService.createMember("Sam Ortiz", "sam@example.com", "dana@ledgerly.test"))
				.isInstanceOf(BusinessRuleException.class);
		verify(userRepository, never()).save(any());
	}

	@Test
	void changePasswordWithWrongCurrentPasswordIsRefused() {
		AppUser sam = new AppUser("sam@example.com", passwordEncoder.encode("temporary-pass"), "Sam", Role.MEMBER,
				"M000001", true, NOW);
		when(userRepository.findByEmail("sam@example.com")).thenReturn(Optional.of(sam));

		assertThatThrownBy(() -> userService.changePassword("sam@example.com", "wrong", "a-new-long-password-1"))
				.isInstanceOf(BadRequestException.class);
		assertThat(sam.isMustChangePassword()).isTrue();
	}

	@Test
	void changePasswordStoresNewHashAndClearsTheTemporaryFlag() {
		AppUser sam = withId(new AppUser("sam@example.com", passwordEncoder.encode("temporary-pass"), "Sam", Role.MEMBER,
				"M000001", true, NOW), 7L);
		when(userRepository.findByEmail("sam@example.com")).thenReturn(Optional.of(sam));

		userService.changePassword("sam@example.com", "temporary-pass", "a-new-long-password-1");

		assertThat(sam.isMustChangePassword()).isFalse();
		assertThat(passwordEncoder.matches("a-new-long-password-1", sam.getPasswordHash())).isTrue();
	}

	/** Entities only get an id when the database saves them; tests without a database set one directly. */
	private static AppUser withId(AppUser user, Long id) {
		ReflectionTestUtils.setField(user, "id", id);
		return user;
	}

}
