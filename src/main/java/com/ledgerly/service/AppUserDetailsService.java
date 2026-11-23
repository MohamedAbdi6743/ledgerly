package com.ledgerly.service;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.ledgerly.entity.AppUser;
import com.ledgerly.repository.UserRepository;

/** Tells Spring Security how to load a user at login. Spring then checks the BCrypt hash itself. */
@Service
public class AppUserDetailsService implements UserDetailsService {

	private final UserRepository userRepository;

	public AppUserDetailsService(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	@Override
	@Transactional(readOnly = true)
	public UserDetails loadUserByUsername(String email) {
		AppUser user = userRepository.findByEmail(UserService.normalizeEmail(email))
				.orElseThrow(() -> new UsernameNotFoundException("No account for this email"));
		return User.withUsername(user.getEmail())
				.password(user.getPasswordHash())
				.roles(user.getRole().name())
				.build();
	}

}
