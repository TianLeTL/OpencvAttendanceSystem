package com.smartattendance.repository;

import com.smartattendance.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Integer> {

    /**
     * Find user by username
     */
    Optional<User> findByUsername(String username);

    /**
     * Find user by email
     */
    Optional<User> findByEmail(String email);

    /**
     * Check if username already exists
     */
    boolean existsByUsername(String username);

    /**
     * Check if email already exists
     */
    boolean existsByEmail(String email);


    /**
     * Find users by role
     */
    List<User> findByRole(User.Role role);

    /**
     * Check if username exists excluding specific user ID (for updates)
     */
    boolean existsByUsernameAndUserIdNot(String username, Integer userId);

    /**
     * Check if email exists excluding specific user ID (for updates)
     */
    boolean existsByEmailAndUserIdNot(String email, Integer userId);

    /**
     * Custom query to find user by username or email for authentication
     */
    @Query("SELECT u FROM User u WHERE u.username = :usernameOrEmail OR u.email = :usernameOrEmail")
    Optional<User> findByUsernameOrEmail(@Param("usernameOrEmail") String usernameOrEmail);
}