package com.smartattendance.config;

import com.smartattendance.service.auth.CustomUserDetailsService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;
    private final PasswordEncoder passwordEncoder;

    public SecurityConfig(CustomUserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        this.userDetailsService = userDetailsService;
        this.passwordEncoder = passwordEncoder;
    }

    @Bean
    public AuthenticationManager authenticationManager() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setPasswordEncoder(passwordEncoder);
        provider.setUserDetailsService(userDetailsService);
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .cors(cors -> {})
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/",
                    "/index.html",
                    "/signup.html",
                    "/signup-admin.html",
                    "/signup-professor.html",
                    "/signup-ta.html",
                    "/signup-**",
                    "/home.html",
                    "/sessions.html",
                    "/session-view.html",
                    "/mark-attendance.html",
                    "/students.html",
                    "/student-view.html",
                    "/courses.html",
                    "/settings.html",
                    "/error.html",
                    "/static/**",
                    "/js/**",
                    "/css/**",
                    "/images/**",
                    "/fonts/**",
                    "/logout-modal.js",
                    "/favicon.ico",
                    "/api/recognition/**",
                    "/api/auth/signup",
                    "/api/auth/login",
                    "/api/auth/health",
                    "/api/auth/logout")
                .permitAll()
                .anyRequest().authenticated()
            )
            .httpBasic(httpBasic -> httpBasic.authenticationEntryPoint((request, response, authException) -> {
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.getWriter().write("{\"success\":false,\"message\":\"Unauthorized\"}");
            }))
            .formLogin(form -> form.disable())
            .logout(logout -> logout.disable());

        return http.build();
    }
}
