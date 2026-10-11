package com.smartattendance.service;

import com.smartattendance.entity.Course;
import com.smartattendance.repository.CourseRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class CourseService {

    private final CourseRepository courseRepository;

    @Autowired
    public CourseService(CourseRepository courseRepository) {
        this.courseRepository = courseRepository;
    }

    /**
     * Create a new course
     */
    public Course createCourse(String courseCode, String courseName, String description) {
        // Validate input
        validateCourseInput(courseCode, courseName);

        // Check if course code already exists
        if (courseRepository.existsByCourseCode(courseCode)) {
            throw new IllegalArgumentException("Course code already exists");
        }

        if (courseRepository.existsByCourseNameIgnoreCase(courseName)) {
            throw new IllegalArgumentException("Course name already exists");
        }

        // Create new course
        Course course = new Course(courseCode, courseName, description);

        return courseRepository.save(course);
    }

    /**
     * Get all courses
     */
    @Transactional(readOnly = true)
    public List<Course> getAllCourses() {
        return courseRepository.findAll();
    }

    /**
     * Get course by ID
     */
    @Transactional(readOnly = true)
    public Optional<Course> getCourseById(Integer courseId) {
        return courseRepository.findById(courseId);
    }

    /**
     * Get course by code
     */
    @Transactional(readOnly = true)
    public Optional<Course> getCourseByCode(String courseCode) {
        return courseRepository.findByCourseCode(courseCode);
    }

    /**
     * Search courses by code (case-sensitive)
     */
    @Transactional(readOnly = true)
    public List<Course> searchCoursesByCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return getAllCourses();
        }
        return courseRepository.findByCourseCodeContainingCaseSensitive(code.trim());
    }

    /**
     * Search courses by name (case-sensitive)
     */
    @Transactional(readOnly = true)
    public List<Course> searchCoursesByName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return getAllCourses();
        }
        return courseRepository.findByCourseNameContainingCaseSensitive(name.trim());
    }

    /**
     * Search courses by code or name (case-insensitive)
     */
    @Transactional(readOnly = true)
    public List<Course> searchCourses(String term) {
        if (term == null || term.trim().isEmpty()) {
            return getAllCourses();
        }
        return courseRepository.searchByCodeOrName(term.trim());
    }

    /**
     * Update course
     */
    public Course updateCourse(Integer courseId, String courseCode, String courseName, String description) {
        Optional<Course> courseOpt = courseRepository.findById(courseId);
        if (courseOpt.isEmpty()) {
            throw new IllegalArgumentException("Course not found");
        }

        Course course = courseOpt.get();

        // Validate input
        validateCourseInput(courseCode, courseName);

        // Check if course code already exists (excluding current course)
        if (courseRepository.existsByCourseCodeAndCourseIdNot(courseCode, courseId)) {
            throw new IllegalArgumentException("Course code already exists");
        }

        if (courseRepository.existsByCourseNameIgnoreCaseAndCourseIdNot(courseName, courseId)) {
            throw new IllegalArgumentException("Course name already exists");
        }

        // Update fields
        course.setCourseCode(courseCode);
        course.setCourseName(courseName);
        course.setDescription(description);

        return courseRepository.save(course);
    }

    /**
     * Delete course
     */
    public void deleteCourse(Integer courseId) {
        Optional<Course> courseOpt = courseRepository.findById(courseId);
        if (courseOpt.isEmpty()) {
            throw new IllegalArgumentException("Course not found");
        }

        courseRepository.delete(courseOpt.get());
    }

    /**
     * Validate course input
     */
    private void validateCourseInput(String courseCode, String courseName) {
        if (courseCode == null || courseCode.trim().isEmpty()) {
            throw new IllegalArgumentException("Course code is required");
        }

        if (courseName == null || courseName.trim().isEmpty()) {
            throw new IllegalArgumentException("Course name is required");
        }

        if (courseCode.length() > 20) {
            throw new IllegalArgumentException("Course code cannot exceed 20 characters");
        }

        if (courseName.length() > 200) {
            throw new IllegalArgumentException("Course name cannot exceed 200 characters");
        }
    }

    /**
     * Check if course code is available
     */
    @Transactional(readOnly = true)
    public boolean isCourseCodeAvailable(String courseCode) {
        return !courseRepository.existsByCourseCode(courseCode);
    }
}
