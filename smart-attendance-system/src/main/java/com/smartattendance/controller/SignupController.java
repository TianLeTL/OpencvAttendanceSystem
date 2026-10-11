package com.smartattendance.controller;

import com.smartattendance.entity.User;
import com.smartattendance.service.UserService;
import com.smartattendance.service.auth.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*") // Allow frontend to call this API
public class SignupController {

    private final UserService userService;
    private final AuthenticationManager authenticationManager;
    private final String adminInviteCode;
    private final String professorInviteCode;
    private final String taInviteCode;

    @Autowired
    public SignupController(UserService userService,
                            AuthenticationManager authenticationManager,
                            @Value("${signup.invite.admin}") String adminInviteCode,
                            @Value("${signup.invite.professor}") String professorInviteCode,
                            @Value("${signup.invite.ta}") String taInviteCode) {
        this.userService = userService;
        this.authenticationManager = authenticationManager;
        this.adminInviteCode = adminInviteCode;
        this.professorInviteCode = professorInviteCode;
        this.taInviteCode = taInviteCode;
    }

    /**
     * DTO for signup request
     */
    public static class SignupRequest {
        private String username;
        private String password;
        private String email;
        private String fullName;
        private String role;
        private String inviteCode;

        // Default constructor
        public SignupRequest() {}

        // Getters and setters
        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getFullName() {
            return fullName;
        }

        public void setFullName(String fullName) {
            this.fullName = fullName;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getInviteCode() {
            return inviteCode;
        }

        public void setInviteCode(String inviteCode) {
            this.inviteCode = inviteCode;
        }
    }

    /**
     * DTO for API response
     */
    public static class ApiResponse {
        private boolean success;
        private String message;
        private Object data;

        public ApiResponse(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public ApiResponse(boolean success, String message, Object data) {
            this.success = success;
            this.message = message;
            this.data = data;
        }

        // Getters and setters
        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public Object getData() {
            return data;
        }

        public void setData(Object data) {
            this.data = data;
        }
    }

    /**
     * Register new user endpoint
     */
    @PostMapping("/signup")
    public ResponseEntity<ApiResponse> signup(@Valid @RequestBody SignupRequest request) {
        try {
            // Validate required fields
            if (request.getUsername() == null || request.getUsername().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "Username is required"));
            }

            if (request.getPassword() == null || request.getPassword().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "Password is required"));
            }

            if (request.getFullName() == null || request.getFullName().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "Full name is required"));
            }

            if (request.getEmail() == null || request.getEmail().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(new ApiResponse(false, "Email is required"));
            }

            if (request.getRole() == null || request.getRole().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "Role is required"));
            }

            if (request.getInviteCode() == null || request.getInviteCode().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(new ApiResponse(false, "Invite code is required"));
            }

            User.Role role;
            try {
                role = User.Role.valueOf(request.getRole().trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "Invalid role specified"));
            }

            if (!isInviteCodeValid(role, request.getInviteCode().trim())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(new ApiResponse(false, "Invalid invite code"));
            }

            // Register the user
            String trimmedEmail = request.getEmail().trim();

            User user = userService.registerUser(
                request.getUsername().trim(),
                request.getPassword(),
                trimmedEmail,
                request.getFullName().trim(),
                role
            );

            // Return success response (don't send password hash back)
            return ResponseEntity.ok(new ApiResponse(true, "User registered successfully", convertUserToMap(user)));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(new ApiResponse(false, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse(false, "An error occurred during registration: " + e.getMessage()));
        }
    }

    /**
     * Check username availability
     */
    @GetMapping("/check-username")
    public ResponseEntity<ApiResponse> checkUsername(@RequestParam String username) {
        try {
            boolean available = userService.isUsernameAvailable(username);
            Map<String, Object> data = new HashMap<>();
            data.put("username", username);
            data.put("available", available);

            return ResponseEntity.ok(new ApiResponse(true, "Username check completed", data));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse(false, "Error checking username availability"));
        }
    }

    /**
     * Check email availability
     */
    @GetMapping("/check-email")
    public ResponseEntity<ApiResponse> checkEmail(@RequestParam String email) {
        try {
            boolean available = userService.isEmailAvailable(email);
            Map<String, Object> data = new HashMap<>();
            data.put("email", email);
            data.put("available", available);

            return ResponseEntity.ok(new ApiResponse(true, "Email check completed", data));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse(false, "Error checking email availability"));
        }
    }

    /**
     * DTO for login request
     */
    public static class LoginRequest {
        private String usernameOrEmail;
        private String password;

        // Default constructor
        public LoginRequest() {}

        // Getters and setters
        public String getUsernameOrEmail() {
            return usernameOrEmail;
        }

        public void setUsernameOrEmail(String usernameOrEmail) {
            this.usernameOrEmail = usernameOrEmail;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }
    }

    /**
     * Login user endpoint
     */
    @PostMapping("/login")
    public ResponseEntity<ApiResponse> login(@Valid @RequestBody LoginRequest request) {
        try {
            // Validate required fields
            if (request.getUsernameOrEmail() == null || request.getUsernameOrEmail().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "Username or email is required"));
            }

            if (request.getPassword() == null || request.getPassword().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "Password is required"));
            }

            Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                    request.getUsernameOrEmail().trim(),
                    request.getPassword()
                )
            );

            UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
            userService.updateLastLogin(principal.getUser().getUsername());
            return ResponseEntity.ok(new ApiResponse(true, "Login successful", convertUserToMap(principal.getUser())));

        } catch (BadCredentialsException e) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiResponse(false, "Invalid username/email or password"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(new ApiResponse(false, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse(false, "An error occurred during login: " + e.getMessage()));
        }
    }

    /**
     * Logout user endpoint
     */
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse> logout() {
        // For now, just return success since we're not implementing session management yet
        // In a real application, you would invalidate the session/token here
        return ResponseEntity.ok(new ApiResponse(true, "Logout successful"));
    }

    /**
     * Get current user info (requires authentication)
     */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse> getCurrentUser(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiResponse(false, "Authentication required"));
        }
        return ResponseEntity.ok(new ApiResponse(true, "Current user retrieved", convertUserToMap(principal.getUser())));
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<ApiResponse> health() {
        return ResponseEntity.ok(new ApiResponse(true, "Auth service is running"));
    }

    private Map<String, Object> convertUserToMap(User user) {
        Map<String, Object> userData = new HashMap<>();
        userData.put("userId", user.getUserId());
        userData.put("username", user.getUsername());
        userData.put("email", user.getEmail());
        userData.put("fullName", user.getFullName());
        userData.put("role", user.getRole());
        userData.put("createdAt", user.getCreatedAt());
        userData.put("lastLogin", user.getLastLogin());
        return userData;
    }

    private boolean isInviteCodeValid(User.Role role, String inviteCode) {
        return switch (role) {
            case ADMIN -> inviteCode.equals(adminInviteCode);
            case PROFESSOR -> inviteCode.equals(professorInviteCode);
            case TEACHING_ASSISTANT -> inviteCode.equals(taInviteCode);
        };
    }
}
