package com.smartattendance.repository;

import com.smartattendance.entity.Course;
import com.smartattendance.entity.Section;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SectionRepository extends JpaRepository<Section, Integer> {

    /**
     * Find sections by course
     */
    List<Section> findByCourse(Course course);

    /**
     * Find sections by course ID
     */
    List<Section> findByCourseCourseId(Integer courseId);

    /**
     * Find section by course and section code
     */
    Optional<Section> findByCourseAndSectionCode(Course course, String sectionCode);

    /**
     * Find section by course ID and section code
     */
    Optional<Section> findByCourseCourseIdAndSectionCode(Integer courseId, String sectionCode);

    /**
     * Check if section code exists for a course
     */
    boolean existsByCourseAndSectionCode(Course course, String sectionCode);

    /**
     * Check if section code exists for a course ID
     */
    boolean existsByCourseCourseIdAndSectionCode(Integer courseId, String sectionCode);

    /**
     * Search sections by instructor name (case-sensitive partial match)
     */
    List<Section> findByInstructorNameContaining(String instructorName);

    /**
     * Find sections assigned to a specific professor
     */
    List<Section> findByProfessorUserId(Integer userId);

    /**
     * Find sections where the user is one of the teaching assistants
     */
    List<Section> findByTeachingAssistant1UserIdOrTeachingAssistant2UserId(Integer assistant1Id, Integer assistant2Id);

    /**
     * Check if section code exists for a course excluding specific section ID
     */
    @Query("SELECT COUNT(s) > 0 FROM Section s WHERE s.course.courseId = :courseId AND s.sectionCode = :sectionCode AND s.sectionId != :sectionId")
    boolean existsByCourseCourseIdAndSectionCodeAndSectionIdNot(@Param("courseId") Integer courseId, @Param("sectionCode") String sectionCode, @Param("sectionId") Integer sectionId);
}
