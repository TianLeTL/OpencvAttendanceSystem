package com.smartattendance.controller;

import com.smartattendance.entity.User;
import com.smartattendance.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    public record ApiResponse(boolean success, String message, Object data) {
        public ApiResponse(boolean success, String message) {
            this(success, message, null);
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse> listUsersByRole(@RequestParam(name = "role") String roleParam,
                                                       @RequestParam(name = "search", required = false) String search) {
        if (roleParam == null || roleParam.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "Role parameter is required"));
        }

        final User.Role role;
        try {
            role = User.Role.valueOf(roleParam.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ApiResponse(false, "Unknown role: " + roleParam));
        }

        List<User> users = userService.getUsersByRole(role);
        String trimmedSearch = search != null ? search.trim().toLowerCase() : null;
        if (trimmedSearch != null && !trimmedSearch.isEmpty()) {
            users = users.stream()
                    .filter(user -> matchesSearch(user, trimmedSearch))
                    .collect(Collectors.toList());
        }

        Comparator<User> userComparator = Comparator
                .comparing((User user) -> user.getFullName() != null ? user.getFullName() : user.getUsername(),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(User::getUsername, String.CASE_INSENSITIVE_ORDER);

        users = users.stream()
                .sorted(userComparator)
                .collect(Collectors.toList());

        List<Map<String, Object>> data = users.stream()
                .map(this::convertUserToMap)
                .toList();

        return ResponseEntity.ok(new ApiResponse(true, "Users retrieved successfully", data));
    }

    private boolean matchesSearch(User user, String searchTerm) {
        return (user.getFullName() != null && user.getFullName().toLowerCase().contains(searchTerm))
                || (user.getUsername() != null && user.getUsername().toLowerCase().contains(searchTerm))
                || (user.getEmail() != null && user.getEmail().toLowerCase().contains(searchTerm));
    }

    private Map<String, Object> convertUserToMap(User user) {
        Map<String, Object> map = new HashMap<>();
        map.put("userId", user.getUserId());
        map.put("username", user.getUsername());
        map.put("fullName", user.getFullName());
        map.put("email", user.getEmail());
        map.put("role", user.getRole());
        map.put("createdAt", user.getCreatedAt());
        map.put("lastLogin", user.getLastLogin());
        return map;
    }
}
