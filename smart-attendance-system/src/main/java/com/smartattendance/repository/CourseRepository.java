package com.smartattendance.repository;

import com.smartattendance.entity.Course;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CourseRepository extends JpaRepository<Course, Integer> {

    /**
     * Find course by course code
     */
    Optional<Course> findByCourseCode(String courseCode);

    /**
     * Check if course code exists
     */
    boolean existsByCourseCode(String courseCode);

    /**
     * Search courses by code (case-sensitive partial match)
     */
    @Query("SELECT c FROM Course c WHERE c.courseCode LIKE CONCAT('%', :code, '%')")
    List<Course> findByCourseCodeContainingCaseSensitive(@Param("code") String code);

    /**
     * Search courses by name (case-sensitive partial match)
     */
    @Query("SELECT c FROM Course c WHERE c.courseName LIKE CONCAT('%', :name, '%')")
    List<Course> findByCourseNameContainingCaseSensitive(@Param("name") String name);

    /**
     * Search courses by code or name (case-insensitive)
     */
    @Query("""
        SELECT c FROM Course c
        WHERE LOWER(c.courseCode) LIKE LOWER(CONCAT('%', :term, '%'))
           OR LOWER(c.courseName) LIKE LOWER(CONCAT('%', :term, '%'))
    """)
    List<Course> searchByCodeOrName(@Param("term") String term);

    /**
     * Check if course code exists excluding specific course ID (for updates)
     */
    boolean existsByCourseCodeAndCourseIdNot(String courseCode, Integer excludeCourseId);

    /**
     * Check if a course name exists (case-insensitive)
     */
    boolean existsByCourseNameIgnoreCase(String courseName);

    /**
     * Check if a course name exists for other records (case-insensitive)
     */
    boolean existsByCourseNameIgnoreCaseAndCourseIdNot(String courseName, Integer excludeCourseId);
}
