package com.smartattendance.controller;

import com.smartattendance.entity.Course;
import com.smartattendance.entity.Section;
import com.smartattendance.entity.User;
import com.smartattendance.security.SecurityUtils;
import com.smartattendance.service.CourseService;
import com.smartattendance.service.SectionService;
import com.smartattendance.service.UserService;
import com.smartattendance.service.auth.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/courses")
@CrossOrigin(origins = "*")
public class CourseController {

    private final CourseService courseService;
    private final SectionService sectionService;
    private final UserService userService;

    @Autowired
    public CourseController(CourseService courseService,
                            SectionService sectionService,
                            UserService userService) {
        this.courseService = courseService;
        this.sectionService = sectionService;
        this.userService = userService;
    }

    /**
     * DTO for course creation/update request
     */
    public static class CourseRequest {
        private String courseCode;
        private String courseName;
        private String description;

        // Getters and setters
        public String getCourseCode() { return courseCode; }
        public void setCourseCode(String courseCode) { this.courseCode = courseCode; }

        public String getCourseName() { return courseName; }
        public void setCourseName(String courseName) { this.courseName = courseName; }

        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    /**
     * DTO for API response
     */
    public class ApiResponse {
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
        public boolean isSuccess() { return success; }
        public void setSuccess(boolean success) { this.success = success; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public Object getData() { return data; }
        public void setData(Object data) { this.data = data; }
    }

    /**
     * Get all courses with optional search
     */
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','PROFESSOR','TEACHING_ASSISTANT')")
    public ResponseEntity<ApiResponse> getAllCourses(@RequestParam(required = false) String search,
                                                    @RequestParam(required = false) String searchBy) {
        try {
            User currentUser = getCurrentUser();
            String term = search != null ? search.trim() : null;
            List<Course> courses;

            if (currentUser.getRole() == User.Role.ADMIN) {
                if (term != null && !term.isEmpty()) {
                    if (searchBy != null) {
                        if ("code".equalsIgnoreCase(searchBy)) {
                            courses = courseService.searchCoursesByCode(term);
                        } else if ("name".equalsIgnoreCase(searchBy)) {
                            courses = courseService.searchCoursesByName(term);
                        } else {
                            courses = courseService.searchCourses(term);
                        }
                    } else {
                        courses = courseService.searchCourses(term);
                    }
                } else {
                    courses = courseService.getAllCourses();
                }
            } else {
                courses = getCoursesVisibleToUser(currentUser);
                if (term != null && !term.isEmpty()) {
                    final String lowered = term.toLowerCase(Locale.ROOT);
                    courses = courses.stream()
                            .filter(course -> matchesSearch(course, lowered, searchBy))
                            .toList();
                }
            }

            List<Map<String, Object>> courseData = courses.stream()
                    .map(this::convertCourseToMap)
                    .toList();

            return ResponseEntity.ok(new ApiResponse(true, "Courses retrieved successfully", courseData));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error retrieving courses: " + e.getMessage()));
        }
    }

    /**
     * Get course by ID
     */
    @GetMapping("/{courseId}")
    @PreAuthorize("hasAnyRole('ADMIN','PROFESSOR','TEACHING_ASSISTANT')")
    public ResponseEntity<ApiResponse> getCourseById(@PathVariable Integer courseId) {
        try {
            User currentUser = getCurrentUser();
            Optional<Course> courseOpt = courseService.getCourseById(courseId);
            if (courseOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            Map<String, Object> courseData = convertCourseToMap(courseOpt.get());

            if (currentUser.getRole() != User.Role.ADMIN) {
                boolean accessible = getCoursesVisibleToUser(currentUser).stream()
                        .anyMatch(course -> Objects.equals(course.getCourseId(), courseId));
                if (!accessible) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body(new ApiResponse(false, "You are not permitted to view this course"));
                }
            }
            return ResponseEntity.ok(new ApiResponse(true, "Course retrieved successfully", courseData));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error retrieving course: " + e.getMessage()));
        }
    }

    /**
     * Create new course
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse> createCourse(@Valid @RequestBody CourseRequest request) {
        try {
            if (request.getCourseCode() == null || request.getCourseCode().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(new ApiResponse(false, "Course code is required"));
            }

            if (request.getCourseName() == null || request.getCourseName().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(new ApiResponse(false, "Course name is required"));
            }

            Course course = courseService.createCourse(
                    request.getCourseCode().trim(),
                    request.getCourseName().trim(),
                    request.getDescription() != null ? request.getDescription().trim() : null
            );

            Map<String, Object> courseData = convertCourseToMap(course);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new ApiResponse(true, "Course created successfully", courseData));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error creating course: " + e.getMessage()));
        }
    }

    /**
     * Update course
     */
    @PutMapping("/{courseId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse> updateCourse(@PathVariable Integer courseId,
                                                   @Valid @RequestBody CourseRequest request) {
        try {
            if (request.getCourseCode() == null || request.getCourseCode().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(new ApiResponse(false, "Course code is required"));
            }

            if (request.getCourseName() == null || request.getCourseName().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(new ApiResponse(false, "Course name is required"));
            }

            Course course = courseService.updateCourse(
                    courseId,
                    request.getCourseCode().trim(),
                    request.getCourseName().trim(),
                    request.getDescription() != null ? request.getDescription().trim() : null
            );

            Map<String, Object> courseData = convertCourseToMap(course);
            return ResponseEntity.ok(new ApiResponse(true, "Course updated successfully", courseData));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error updating course: " + e.getMessage()));
        }
    }

    /**
     * Delete course
     */
    @DeleteMapping("/{courseId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse> deleteCourse(@PathVariable Integer courseId) {
        try {
            courseService.deleteCourse(courseId);
            return ResponseEntity.ok(new ApiResponse(true, "Course deleted successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error deleting course: " + e.getMessage()));
        }
    }

    /**
     * Check course code availability
     */
    @GetMapping("/check-code")
    public ResponseEntity<ApiResponse> checkCourseCode(@RequestParam String courseCode) {
        try {
            boolean available = courseService.isCourseCodeAvailable(courseCode);
            Map<String, Object> data = new HashMap<>();
            data.put("courseCode", courseCode);
            data.put("available", available);

            return ResponseEntity.ok(new ApiResponse(true, "Course code check completed", data));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error checking course code availability"));
        }
    }

    /**
     * Helper method to convert Course entity to Map
     */
    private Map<String, Object> convertCourseToMap(Course course) {
        Map<String, Object> map = new HashMap<>();
        map.put("courseId", course.getCourseId());
        map.put("courseCode", course.getCourseCode());
        map.put("courseName", course.getCourseName());
        map.put("description", course.getDescription());
        map.put("createdAt", course.getCreatedAt());
        map.put("updatedAt", course.getUpdatedAt());
        return map;
    }

    private boolean matchesSearch(Course course, String loweredTerm, String searchBy) {
        if (searchBy != null) {
            if ("code".equalsIgnoreCase(searchBy)) {
                return course.getCourseCode() != null &&
                        course.getCourseCode().toLowerCase(Locale.ROOT).contains(loweredTerm);
            }
            if ("name".equalsIgnoreCase(searchBy)) {
                return course.getCourseName() != null &&
                        course.getCourseName().toLowerCase(Locale.ROOT).contains(loweredTerm);
            }
        }
        return (course.getCourseCode() != null &&
                course.getCourseCode().toLowerCase(Locale.ROOT).contains(loweredTerm))
                || (course.getCourseName() != null &&
                course.getCourseName().toLowerCase(Locale.ROOT).contains(loweredTerm));
    }

    private List<Course> getCoursesVisibleToUser(User user) {
        if (user == null) {
            return List.of();
        }
        if (user.getRole() == User.Role.ADMIN) {
            return courseService.getAllCourses();
        }
        List<Section> sections = sectionService.getSectionsVisibleTo(user);
        LinkedHashMap<Integer, Course> coursesById = new LinkedHashMap<>();
        for (Section section : sections) {
            Course course = section.getCourse();
            if (course != null && course.getCourseId() != null) {
                coursesById.putIfAbsent(course.getCourseId(), course);
            }
        }
        return new ArrayList<>(coursesById.values());
    }

    private User getCurrentUser() {
        UserPrincipal principal = SecurityUtils.getCurrentUserPrincipal()
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
        return userService.getUserById(principal.getUser().getUserId())
                .orElse(principal.getUser());
    }
}
