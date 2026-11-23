package com.ledgerly.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers the temporary-password check for the API, but not for login, logout, "me" or changing the password. */
@Configuration
public class WebConfig implements WebMvcConfigurer {

	private final PasswordChangeInterceptor passwordChangeInterceptor;

	public WebConfig(PasswordChangeInterceptor passwordChangeInterceptor) {
		this.passwordChangeInterceptor = passwordChangeInterceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(passwordChangeInterceptor)
				.addPathPatterns("/api/**")
				.excludePathPatterns("/api/auth/**");
	}

}
