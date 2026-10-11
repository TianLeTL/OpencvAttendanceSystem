package com.smartattendance.repository;

import com.smartattendance.entity.Section;
import com.smartattendance.entity.SectionEnrolments;
import com.smartattendance.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Repository
public interface SectionEnrolmentsRepository extends JpaRepository<SectionEnrolments, Integer> {

    /**
     * Find enrolments by section
     */
    List<SectionEnrolments> findBySection(Section section);

    /**
     * Find enrolments by section ID
     */
    List<SectionEnrolments> findBySectionSectionId(Integer sectionId);

    /**
     * Find enrolments by student
     */
    List<SectionEnrolments> findByStudent(Student student);

    /**
     * Find enrolments by student ID
     */
    List<SectionEnrolments> findByStudentStudentId(String studentId);

    /**
     * Find specific enrolment by section and student
     */
    Optional<SectionEnrolments> findBySectionAndStudent(Section section, Student student);

    /**
     * Find specific enrolment by section ID and student ID
     */
    Optional<SectionEnrolments> findBySectionSectionIdAndStudentStudentId(Integer sectionId, String studentId);

    /**
     * Check if student is enrolled in section
     */
    boolean existsBySectionAndStudent(Section section, Student student);

    /**
     * Check if student is enrolled in section by IDs
     */
    boolean existsBySectionSectionIdAndStudentStudentId(Integer sectionId, String studentId);

    /**
     * Find enrolments by status
     */
    List<SectionEnrolments> findByStatus(SectionEnrolments.EnrolmentStatus status);

    /**
     * Find enrolments by section and status
     */
    List<SectionEnrolments> findBySectionAndStatus(Section section, SectionEnrolments.EnrolmentStatus status);

    /**
     * Find enrolments by section ID and status
     */
    List<SectionEnrolments> findBySectionSectionIdAndStatus(Integer sectionId, SectionEnrolments.EnrolmentStatus status);

    /**
     * Check if a student is already enrolled (by status) in another section of the same course.
     */
    boolean existsByStudentStudentIdAndSectionCourseCourseIdAndStatus(String studentId,
                                                                      Integer courseId,
                                                                      SectionEnrolments.EnrolmentStatus status);

    /**
     * Find a single enrollment for a student within a course with the given status.
     */
    Optional<SectionEnrolments> findFirstByStudentStudentIdAndSectionCourseCourseIdAndStatus(String studentId,
                                                                                            Integer courseId,
                                                                                            SectionEnrolments.EnrolmentStatus status);

    /**
     * Delete enrolment by section and student
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM SectionEnrolments se WHERE se.section = :section AND se.student = :student")
    void deleteBySectionAndStudent(@Param("section") Section section, @Param("student") Student student);

    /**
     * Delete enrolment by section ID and student ID
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM SectionEnrolments se WHERE se.section.sectionId = :sectionId AND se.student.studentId = :studentId")
    void deleteBySectionSectionIdAndStudentStudentId(@Param("sectionId") Integer sectionId, @Param("studentId") String studentId);
}
