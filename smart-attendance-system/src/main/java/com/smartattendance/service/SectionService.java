package com.smartattendance.service;

import com.smartattendance.entity.Course;
import com.smartattendance.entity.Section;
import com.smartattendance.entity.User;
import com.smartattendance.repository.CourseRepository;
import com.smartattendance.repository.SectionRepository;
import com.smartattendance.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Service
@Transactional
public class SectionService {

    private final SectionRepository sectionRepository;
    private final CourseRepository courseRepository;
    private final UserRepository userRepository;

    @Autowired
    public SectionService(SectionRepository sectionRepository, CourseRepository courseRepository, UserRepository userRepository) {
        this.sectionRepository = sectionRepository;
        this.courseRepository = courseRepository;
        this.userRepository = userRepository;
    }

    /**
     * Create a new section
     */
    public Section createSection(Integer courseId,
                                 String sectionCode,
                                 String instructorName,
                                 Integer maxCapacity) {
        return createSection(courseId, sectionCode, instructorName, maxCapacity, null, null, null);
    }

    public Section createSection(Integer courseId,
                                 String sectionCode,
                                 String instructorName,
                                 Integer maxCapacity,
                                 Integer professorUserId,
                                 Integer teachingAssistant1UserId,
                                 Integer teachingAssistant2UserId) {
        // Validate input
        validateSectionInput(sectionCode);

        // Find course
        Optional<Course> courseOpt = courseRepository.findById(courseId);
        if (courseOpt.isEmpty()) {
            throw new IllegalArgumentException("Course not found");
        }

        Course course = courseOpt.get();

        // Check if section code already exists for this course
        if (sectionRepository.existsByCourseCourseIdAndSectionCode(courseId, sectionCode)) {
            throw new IllegalArgumentException("Section code already exists for this course");
        }

        User professor = resolveUser(professorUserId, EnumSet.of(User.Role.PROFESSOR), "professorId");
        TeachingAssistants teachingAssistants = resolveTeachingAssistants(teachingAssistant1UserId, teachingAssistant2UserId);

        // Create new section
        Section section = new Section(course, sectionCode, instructorName, maxCapacity);
        section.setProfessor(professor);
        section.setTeachingAssistant1(teachingAssistants.assistant1());
        section.setTeachingAssistant2(teachingAssistants.assistant2());

        return sectionRepository.save(section);
    }

    /**
     * Get all sections
     */
    @Transactional(readOnly = true)
    public List<Section> getAllSections() {
        return sectionRepository.findAll();
    }

    /**
     * Get sections by course
     */
    @Transactional(readOnly = true)
    public List<Section> getSectionsByCourse(Integer courseId) {
        return sectionRepository.findByCourseCourseId(courseId);
    }

    /**
     * Get section by ID
     */
    @Transactional(readOnly = true)
    public Optional<Section> getSectionById(Integer sectionId) {
        return sectionRepository.findById(sectionId);
    }

    /**
     * Update section
     */
    public Section updateSection(Integer sectionId,
                                 String sectionCode,
                                 String instructorName,
                                 Integer maxCapacity) {
        return updateSection(sectionId, sectionCode, instructorName, maxCapacity, null, null, null);
    }

    public Section updateSection(Integer sectionId,
                                 String sectionCode,
                                 String instructorName,
                                 Integer maxCapacity,
                                 Integer professorUserId,
                                 Integer teachingAssistant1UserId,
                                 Integer teachingAssistant2UserId) {
        Optional<Section> sectionOpt = sectionRepository.findById(sectionId);
        if (sectionOpt.isEmpty()) {
            throw new IllegalArgumentException("Section not found");
        }

        Section section = sectionOpt.get();

        // Validate input
        validateSectionInput(sectionCode);

        // Check if section code already exists for this course (excluding current section)
        Integer courseId = section.getCourse().getCourseId();
        if (sectionRepository.existsByCourseCourseIdAndSectionCodeAndSectionIdNot(courseId, sectionCode, sectionId)) {
            throw new IllegalArgumentException("Section code already exists for this course");
        }

        User professor = resolveUser(professorUserId, EnumSet.of(User.Role.PROFESSOR), "professorId");
        TeachingAssistants teachingAssistants = resolveTeachingAssistants(teachingAssistant1UserId, teachingAssistant2UserId);

        // Update fields
        section.setSectionCode(sectionCode);
        section.setInstructorName(instructorName);
        section.setMaxCapacity(maxCapacity);
        section.setProfessor(professor);
        section.setTeachingAssistant1(teachingAssistants.assistant1());
        section.setTeachingAssistant2(teachingAssistants.assistant2());

        return sectionRepository.save(section);
    }

    /**
     * Delete section
     */
    public void deleteSection(Integer sectionId) {
        Optional<Section> sectionOpt = sectionRepository.findById(sectionId);
        if (sectionOpt.isEmpty()) {
            throw new IllegalArgumentException("Section not found");
        }

        sectionRepository.delete(sectionOpt.get());
    }

    /**
     * Search sections by instructor name (case-sensitive)
     */
    @Transactional(readOnly = true)
    public List<Section> searchSectionsByInstructor(String instructorName) {
        if (instructorName == null || instructorName.trim().isEmpty()) {
            return getAllSections();
        }
        return sectionRepository.findByInstructorNameContaining(instructorName.trim());
    }

    @Transactional(readOnly = true)
    public List<Section> getSectionsVisibleTo(User user) {
        if (user == null) {
            return List.of();
        }
        if (user.getRole() == User.Role.ADMIN) {
            return getAllSections();
        }
        LinkedHashSet<Section> sections = new LinkedHashSet<>();
        if (user.getRole() == User.Role.PROFESSOR) {
            sections.addAll(sectionRepository.findByProfessorUserId(user.getUserId()));
        }
        if (user.getRole() == User.Role.TEACHING_ASSISTANT) {
            sections.addAll(sectionRepository.findByTeachingAssistant1UserIdOrTeachingAssistant2UserId(
                    user.getUserId(), user.getUserId()));
        }
        return new ArrayList<>(sections);
    }

    @Transactional(readOnly = true)
    public boolean canUserAccessSection(User user, Section section) {
        if (user == null || section == null) {
            return false;
        }
        if (user.getRole() == User.Role.ADMIN) {
            return true;
        }
        if (user.getRole() == User.Role.PROFESSOR) {
            return section.getProfessor() != null
                    && Objects.equals(section.getProfessor().getUserId(), user.getUserId());
        }
        if (user.getRole() == User.Role.TEACHING_ASSISTANT) {
            boolean ta1 = section.getTeachingAssistant1() != null
                    && Objects.equals(section.getTeachingAssistant1().getUserId(), user.getUserId());
            boolean ta2 = section.getTeachingAssistant2() != null
                    && Objects.equals(section.getTeachingAssistant2().getUserId(), user.getUserId());
            return ta1 || ta2;
        }
        return false;
    }

    /**
     * Validate section input
     */
    private void validateSectionInput(String sectionCode) {
        if (sectionCode == null || sectionCode.trim().isEmpty()) {
            throw new IllegalArgumentException("Section code is required");
        }

        if (sectionCode.length() > 10) {
            throw new IllegalArgumentException("Section code cannot exceed 10 characters");
        }
    }

    private User resolveUser(Integer userId, Set<User.Role> allowedRoles, String fieldName) {
        if (userId == null) {
            return null;
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found for " + fieldName));
        if (!allowedRoles.contains(user.getRole())) {
            throw new IllegalArgumentException("User " + user.getUserId() + " is not permitted for " + fieldName);
        }
        return user;
    }

    private TeachingAssistants resolveTeachingAssistants(Integer firstAssistantId, Integer secondAssistantId) {
        if (firstAssistantId != null && secondAssistantId != null && Objects.equals(firstAssistantId, secondAssistantId)) {
            throw new IllegalArgumentException("Teaching assistant slots must reference different users");
        }
        User assistant1 = resolveUser(firstAssistantId, EnumSet.of(User.Role.TEACHING_ASSISTANT), "teachingAssistant1Id");
        User assistant2 = resolveUser(secondAssistantId, EnumSet.of(User.Role.TEACHING_ASSISTANT), "teachingAssistant2Id");
        return new TeachingAssistants(assistant1, assistant2);
    }

    private record TeachingAssistants(User assistant1, User assistant2) {}
}
