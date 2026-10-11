package com.smartattendance.service;

import com.smartattendance.entity.User;
import com.smartattendance.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Autowired
    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Register a new user
     */
    public User registerUser(String username, String rawPassword, String email, String fullName, User.Role role) {
        // Validate input
        validateRegistrationInput(username, rawPassword, email, fullName, role);

        // Check if username already exists
        if (userRepository.existsByUsername(username)) {
            throw new IllegalArgumentException("Username already exists");
        }

        // Check if email already exists
        if (email != null && userRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email already exists");
        }

        // Hash the password
        String hashedPassword = passwordEncoder.encode(rawPassword);

        // Create new user
        User user = new User(username, hashedPassword, email, fullName);
        user.setRole(role != null ? role : User.Role.ADMIN);
        user.setLastLogin(null); // New users haven't logged in yet

        // Save to database
        return userRepository.save(user);
    }

    /**
     * Find user by username
     */
    @Transactional(readOnly = true)
    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    /**
     * Find user by email
     */
    @Transactional(readOnly = true)
    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    /**
     * Authenticate user with username/email and password
     */
    @Transactional(readOnly = true)
    public boolean authenticateUser(String usernameOrEmail, String rawPassword) {
        Optional<User> userOpt = userRepository.findByUsernameOrEmail(usernameOrEmail);

        if (userOpt.isEmpty()) {
            return false;
        }

        User user = userOpt.get();
        return passwordEncoder.matches(rawPassword, user.getPasswordHash());
    }

    /**
     * Update user's last login time
     */
    public void updateLastLogin(String username) {
        Optional<User> userOpt = userRepository.findByUsername(username);
        if (userOpt.isPresent()) {
            User user = userOpt.get();
            user.setLastLogin(LocalDateTime.now());
            userRepository.save(user);
        }
    }

    /**
     * Validate registration input
     */
    private void validateRegistrationInput(String username, String password, String email, String fullName, User.Role role) {
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Username is required");
        }

        if (password == null || password.isEmpty()) {
            throw new IllegalArgumentException("Password is required");
        }

        if (fullName == null || fullName.trim().isEmpty()) {
            throw new IllegalArgumentException("Full name is required");
        }

        if (username.length() < 3) {
            throw new IllegalArgumentException("Username must be at least 3 characters long");
        }

        if (password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters long");
        }

        // Check for at least one uppercase letter and one special character
        if (!password.matches(".*[A-Z].*")) {
            throw new IllegalArgumentException("Password must contain at least one uppercase letter");
        }

        if (!password.matches(".*[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>\\/?].*")) {
            throw new IllegalArgumentException("Password must contain at least one special character");
        }

        if (role == null) {
            throw new IllegalArgumentException("Role is required");
        }
    }

    /**
     * Check if username is available
     */
    @Transactional(readOnly = true)
    public boolean isUsernameAvailable(String username) {
        return !userRepository.existsByUsername(username);
    }

    /**
     * Check if email is available
     */
    @Transactional(readOnly = true)
    public boolean isEmailAvailable(String email) {
        return email == null || !userRepository.existsByEmail(email);
    }

    /**
     * Authenticate user login
     */
    public User loginUser(String usernameOrEmail, String rawPassword) {
        // Find user by username or email
        Optional<User> userOpt = userRepository.findByUsernameOrEmail(usernameOrEmail);

        if (userOpt.isEmpty()) {
            throw new IllegalArgumentException("Invalid username/email or password");
        }

        User user = userOpt.get();

        // Verify password
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid username/email or password");
        }

        // Update last login time
        user.setLastLogin(LocalDateTime.now());
        userRepository.save(user);

        return user;
    }

    /**
     * Get user by ID
     */
    @Transactional(readOnly = true)
    public Optional<User> getUserById(Integer userId) {
        return userRepository.findById(userId);
    }

    /**
     * Get all users with the specified role.
     */
    @Transactional(readOnly = true)
    public List<User> getUsersByRole(User.Role role) {
        return userRepository.findByRole(role);
    }

    /**
     * Validate login credentials without updating last login
     */
    @Transactional(readOnly = true)
    public boolean validateCredentials(String usernameOrEmail, String rawPassword) {
        try {
            loginUser(usernameOrEmail, rawPassword);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
