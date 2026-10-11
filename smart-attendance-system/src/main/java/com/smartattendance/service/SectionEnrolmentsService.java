package com.smartattendance.service;

import com.smartattendance.entity.Section;
import com.smartattendance.entity.SectionEnrolments;
import com.smartattendance.entity.Student;
import com.smartattendance.repository.SectionEnrolmentsRepository;
import com.smartattendance.repository.SectionRepository;
import com.smartattendance.repository.StudentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class SectionEnrolmentsService {

    private final SectionEnrolmentsRepository sectionEnrolmentsRepository;
    private final SectionRepository sectionRepository;
    private final StudentRepository studentRepository;
    private final SessionService sessionService;

    @Autowired
    public SectionEnrolmentsService(SectionEnrolmentsRepository sectionEnrolmentsRepository,
                                    SectionRepository sectionRepository,
                                    StudentRepository studentRepository,
                                    SessionService sessionService) {
        this.sectionEnrolmentsRepository = sectionEnrolmentsRepository;
        this.sectionRepository = sectionRepository;
        this.studentRepository = studentRepository;
        this.sessionService = sessionService;
    }

    /**
     * Enroll student in section
     */
    public SectionEnrolments enrollStudent(Integer sectionId, String studentId, SectionEnrolments.EnrolmentStatus status) {
        // Find section
        Optional<Section> sectionOpt = sectionRepository.findById(sectionId);
        if (sectionOpt.isEmpty()) {
            throw new IllegalArgumentException("Section not found");
        }

        // Find student
        Optional<Student> studentOpt = studentRepository.findByStudentId(studentId);
        if (studentOpt.isEmpty()) {
            throw new IllegalArgumentException("Student not found");
        }

        Section section = sectionOpt.get();

        // Check if already enrolled in this section
        if (sectionEnrolmentsRepository.existsBySectionSectionIdAndStudentStudentId(sectionId, studentId)) {
            throw new IllegalArgumentException("Student already enrolled in this section");
        }

        // Check if already enrolled in another section of the same course
        Integer courseId = section.getCourse().getCourseId();
        Optional<SectionEnrolments> existingCourseEnrollment =
                sectionEnrolmentsRepository.findFirstByStudentStudentIdAndSectionCourseCourseIdAndStatus(
                        studentId,
                        courseId,
                        SectionEnrolments.EnrolmentStatus.ENROLLED
                );
        if (existingCourseEnrollment.isPresent()) {
            SectionEnrolments existing = existingCourseEnrollment.get();
            String otherSectionCode = existing.getSection().getSectionCode();
            String courseCode = existing.getSection().getCourse().getCourseCode();
            throw new IllegalArgumentException(
                    String.format("Student is already enrolled in section %s (%s). A student may join only one section per course.",
                            otherSectionCode, courseCode)
            );
        }

        // Check capacity
        Integer maxCapacity = section.getMaxCapacity();
        long currentEnrollments = sectionEnrolmentsRepository.findBySectionSectionIdAndStatus(sectionId, SectionEnrolments.EnrolmentStatus.ENROLLED).size();
        if (currentEnrollments >= maxCapacity) {
            throw new IllegalArgumentException("Section is at maximum capacity");
        }

        // Create enrollment
        SectionEnrolments enrollment = new SectionEnrolments(studentOpt.get(), section, LocalDate.now(), status);
        SectionEnrolments saved = sectionEnrolmentsRepository.save(enrollment);

        if (status == SectionEnrolments.EnrolmentStatus.ENROLLED) {
            sessionService.refreshSectionSessionRosters(sectionId, null);
        }

        return saved;
    }

    /**
     * Get enrollments by section
     */
    @Transactional(readOnly = true)
    public List<SectionEnrolments> getEnrollmentsBySection(Integer sectionId) {
        return sectionEnrolmentsRepository.findBySectionSectionId(sectionId);
    }

    /**
     * Get enrollments by student
     */
    @Transactional(readOnly = true)
    public List<SectionEnrolments> getEnrollmentsByStudent(String studentId) {
        return sectionEnrolmentsRepository.findByStudentStudentId(studentId);
    }

    /**
     * Update enrollment status
     */
    public SectionEnrolments updateEnrollmentStatus(Integer sectionId, String studentId, SectionEnrolments.EnrolmentStatus status) {
        Optional<SectionEnrolments> enrollmentOpt = sectionEnrolmentsRepository.findBySectionSectionIdAndStudentStudentId(sectionId, studentId);
        if (enrollmentOpt.isEmpty()) {
            throw new IllegalArgumentException("Enrollment not found");
        }

        SectionEnrolments enrollment = enrollmentOpt.get();
        SectionEnrolments.EnrolmentStatus previousStatus = enrollment.getStatus();
        enrollment.setStatus(status);

        SectionEnrolments updated = sectionEnrolmentsRepository.save(enrollment);

        if (status == SectionEnrolments.EnrolmentStatus.ENROLLED
                && previousStatus != SectionEnrolments.EnrolmentStatus.ENROLLED) {
            sessionService.refreshSectionSessionRosters(sectionId, null);
        }

        return updated;
    }

    /**
     * Remove student from section
     */
    public void removeStudentFromSection(Integer sectionId, String studentId) {
        Optional<SectionEnrolments> enrollmentOpt = sectionEnrolmentsRepository.findBySectionSectionIdAndStudentStudentId(sectionId, studentId);
        if (enrollmentOpt.isEmpty()) {
            throw new IllegalArgumentException("Enrollment not found");
        }

        sectionEnrolmentsRepository.delete(enrollmentOpt.get());
    }

    /**
     * Get enrollment by section and student
     */
    @Transactional(readOnly = true)
    public Optional<SectionEnrolments> getEnrollment(Integer sectionId, String studentId) {
        return sectionEnrolmentsRepository.findBySectionSectionIdAndStudentStudentId(sectionId, studentId);
    }

    /**
     * Get enrollment by id
     */
    @Transactional(readOnly = true)
    public Optional<SectionEnrolments> getEnrollmentById(Integer enrollmentId) {
        return sectionEnrolmentsRepository.findById(enrollmentId);
    }
}
