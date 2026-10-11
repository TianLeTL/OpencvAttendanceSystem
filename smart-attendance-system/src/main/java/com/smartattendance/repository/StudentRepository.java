package com.smartattendance.repository;

import com.smartattendance.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface StudentRepository extends JpaRepository<Student, String> {

    /**
     * Find student by student ID
     */
    Optional<Student> findByStudentId(String studentId);

    /**
     * Check if student ID already exists
     */
    boolean existsByStudentId(String studentId);

    /**
     * Find student by email
     */
    Optional<Student> findByEmail(String email);

    /**
     * Check if email already exists
     */
    boolean existsByEmail(String email);

    /**
     * Search students by name (case-insensitive partial match)
     */
    List<Student> findByFullNameContainingIgnoreCase(String fullName);

    /**
     * Search students by student ID or name (case-insensitive partial match)
     */
    @Query("SELECT s FROM Student s WHERE " +
           "LOWER(s.studentId) LIKE LOWER(CONCAT('%', :searchTerm, '%')) OR " +
           "LOWER(s.fullName) LIKE LOWER(CONCAT('%', :searchTerm, '%'))")
    List<Student> findByStudentIdOrFullNameContainingIgnoreCase(@Param("searchTerm") String searchTerm);

    /**
     * Find students enrolled after specific date
     */
    List<Student> findByEnrolmentDateAfter(LocalDate enrolmentDate);

    /**
     * Check if student ID exists excluding specific student ID (for updates)
     */
    boolean existsByStudentIdAndStudentIdNot(String studentId, String excludeStudentId);

    /**
     * Check if email exists excluding specific student ID (for updates)
     */
    boolean existsByEmailAndStudentIdNot(String email, String excludeStudentId);
}