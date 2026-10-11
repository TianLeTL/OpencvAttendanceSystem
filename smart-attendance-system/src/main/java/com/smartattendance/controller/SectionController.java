package com.smartattendance.controller;

import com.smartattendance.entity.Section;
import com.smartattendance.entity.User;
import com.smartattendance.security.SecurityUtils;
import com.smartattendance.service.SectionService;
import com.smartattendance.service.UserService;
import com.smartattendance.service.auth.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/sections")
@CrossOrigin(origins = "*")
public class SectionController {

    private final SectionService sectionService;
    private final UserService userService;

    @Autowired
    public SectionController(SectionService sectionService, UserService userService) {
        this.sectionService = sectionService;
        this.userService = userService;
    }

    /**
     * DTO for section creation/update request
     */
    public static class SectionRequest {
        private Integer courseId;
        private String sectionCode;
        private String instructorName;
        private Integer maxCapacity;
        private Integer professorUserId;
        private Integer teachingAssistant1UserId;
        private Integer teachingAssistant2UserId;

        // Getters and setters
        public Integer getCourseId() { return courseId; }
        public void setCourseId(Integer courseId) { this.courseId = courseId; }

        public String getSectionCode() { return sectionCode; }
        public void setSectionCode(String sectionCode) { this.sectionCode = sectionCode; }

        public String getInstructorName() { return instructorName; }
        public void setInstructorName(String instructorName) { this.instructorName = instructorName; }

        public Integer getMaxCapacity() { return maxCapacity; }
        public void setMaxCapacity(Integer maxCapacity) { this.maxCapacity = maxCapacity; }

        public Integer getProfessorUserId() {
            return professorUserId;
        }

        public void setProfessorUserId(Integer professorUserId) {
            this.professorUserId = professorUserId;
        }

        public Integer getTeachingAssistant1UserId() {
            return teachingAssistant1UserId;
        }

        public void setTeachingAssistant1UserId(Integer teachingAssistant1UserId) {
            this.teachingAssistant1UserId = teachingAssistant1UserId;
        }

        public Integer getTeachingAssistant2UserId() {
            return teachingAssistant2UserId;
        }

        public void setTeachingAssistant2UserId(Integer teachingAssistant2UserId) {
            this.teachingAssistant2UserId = teachingAssistant2UserId;
        }
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
     * Get all sections with optional search by instructor
     */
    @PreAuthorize("hasAnyRole('ADMIN','PROFESSOR','TEACHING_ASSISTANT')")
    @GetMapping
    public ResponseEntity<ApiResponse> getAllSections(@RequestParam(required = false) String search) {
        try {
            User currentUser = getCurrentUser();
            List<Section> sections;
            if (currentUser.getRole() == User.Role.ADMIN) {
                if (search != null && !search.trim().isEmpty()) {
                    sections = sectionService.searchSectionsByInstructor(search.trim());
                } else {
                    sections = sectionService.getAllSections();
                }
            } else {
                sections = sectionService.getSectionsVisibleTo(currentUser);
                String trimmed = search != null ? search.trim().toLowerCase() : null;
                if (trimmed != null && !trimmed.isEmpty()) {
                    sections = sections.stream()
                            .filter(section -> {
                                if (section.getInstructorName() != null &&
                                        section.getInstructorName().toLowerCase().contains(trimmed)) {
                                    return true;
                                }
                                if (section.getSectionCode() != null &&
                                        section.getSectionCode().toLowerCase().contains(trimmed)) {
                                    return true;
                                }
                                return section.getCourse() != null && section.getCourse().getCourseName() != null &&
                                        section.getCourse().getCourseName().toLowerCase().contains(trimmed);
                            })
                            .toList();
                }
            }

            List<Map<String, Object>> sectionData = sections.stream().map(this::convertSectionToMap).toList();

            return ResponseEntity.ok(new ApiResponse(true, "Sections retrieved successfully", sectionData));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error retrieving sections: " + e.getMessage()));
        }
    }

    /**
     * Get sections by course
     */
    @PreAuthorize("hasAnyRole('ADMIN','PROFESSOR','TEACHING_ASSISTANT')")
    @GetMapping("/course/{courseId}")
    public ResponseEntity<ApiResponse> getSectionsByCourse(@PathVariable Integer courseId) {
        try {
            User currentUser = getCurrentUser();
            List<Section> sections = sectionService.getSectionsByCourse(courseId);
            if (currentUser.getRole() != User.Role.ADMIN) {
                sections = sections.stream()
                        .filter(section -> sectionService.canUserAccessSection(currentUser, section))
                        .toList();
            }
            List<Map<String, Object>> sectionData = sections.stream().map(this::convertSectionToMap).toList();

            return ResponseEntity.ok(new ApiResponse(true, "Sections retrieved successfully", sectionData));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error retrieving sections: " + e.getMessage()));
        }
    }

    /**
     * Get section by ID
     */
    @PreAuthorize("hasAnyRole('ADMIN','PROFESSOR','TEACHING_ASSISTANT')")
    @GetMapping("/{sectionId}")
    public ResponseEntity<ApiResponse> getSectionById(@PathVariable Integer sectionId) {
        try {
            User currentUser = getCurrentUser();
            Optional<Section> sectionOpt = sectionService.getSectionById(sectionId);
            if (sectionOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }
            Section section = sectionOpt.get();
            if (currentUser.getRole() != User.Role.ADMIN &&
                    !sectionService.canUserAccessSection(currentUser, section)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(new ApiResponse(false, "You are not permitted to view this section"));
            }

            Map<String, Object> sectionData = convertSectionToMap(section);
            return ResponseEntity.ok(new ApiResponse(true, "Section retrieved successfully", sectionData));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error retrieving section: " + e.getMessage()));
        }
    }

    /**
     * Create new section
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<ApiResponse> createSection(@Valid @RequestBody SectionRequest request) {
        try {
            if (request.getCourseId() == null) {
                return ResponseEntity.badRequest()
                        .body(new ApiResponse(false, "Course ID is required"));
            }

            if (request.getSectionCode() == null || request.getSectionCode().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(new ApiResponse(false, "Section code is required"));
            }

            Section section = sectionService.createSection(
                    request.getCourseId(),
                    request.getSectionCode().trim(),
                    request.getInstructorName() != null ? request.getInstructorName().trim() : null,
                    request.getMaxCapacity() != null ? request.getMaxCapacity() : 50,
                    request.getProfessorUserId(),
                    request.getTeachingAssistant1UserId(),
                    request.getTeachingAssistant2UserId()
            );

            Map<String, Object> sectionData = convertSectionToMap(section);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new ApiResponse(true, "Section created successfully", sectionData));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error creating section: " + e.getMessage()));
        }
    }

    /**
     * Update section
     */
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{sectionId}")
    public ResponseEntity<ApiResponse> updateSection(@PathVariable Integer sectionId,
                                                    @Valid @RequestBody SectionRequest request) {
        try {
            if (request.getSectionCode() == null || request.getSectionCode().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                        .body(new ApiResponse(false, "Section code is required"));
            }

            Section section = sectionService.updateSection(
                    sectionId,
                    request.getSectionCode().trim(),
                    request.getInstructorName() != null ? request.getInstructorName().trim() : null,
                    request.getMaxCapacity() != null ? request.getMaxCapacity() : 50,
                    request.getProfessorUserId(),
                    request.getTeachingAssistant1UserId(),
                    request.getTeachingAssistant2UserId()
            );

            Map<String, Object> sectionData = convertSectionToMap(section);
            return ResponseEntity.ok(new ApiResponse(true, "Section updated successfully", sectionData));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error updating section: " + e.getMessage()));
        }
    }

    /**
     * Delete section
     */
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{sectionId}")
    public ResponseEntity<ApiResponse> deleteSection(@PathVariable Integer sectionId) {
        try {
            sectionService.deleteSection(sectionId);
            return ResponseEntity.ok(new ApiResponse(true, "Section deleted successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error deleting section: " + e.getMessage()));
        }
    }

    /**
     * Helper method to convert Section entity to Map
     */
    private Map<String, Object> convertSectionToMap(Section section) {
        Map<String, Object> map = new HashMap<>();
        map.put("sectionId", section.getSectionId());
        map.put("courseId", section.getCourse().getCourseId());
        map.put("courseCode", section.getCourse().getCourseCode());
        map.put("sectionCode", section.getSectionCode());
        map.put("instructorName", section.getInstructorName());
        map.put("maxCapacity", section.getMaxCapacity());
        if (section.getProfessor() != null) {
            map.put("professorUserId", section.getProfessor().getUserId());
            map.put("professorName", section.getProfessor().getFullName());
        } else {
            map.put("professorUserId", null);
            map.put("professorName", null);
        }
        if (section.getTeachingAssistant1() != null) {
            map.put("teachingAssistant1UserId", section.getTeachingAssistant1().getUserId());
            map.put("teachingAssistant1Name", section.getTeachingAssistant1().getFullName());
        } else {
            map.put("teachingAssistant1UserId", null);
            map.put("teachingAssistant1Name", null);
        }
        if (section.getTeachingAssistant2() != null) {
            map.put("teachingAssistant2UserId", section.getTeachingAssistant2().getUserId());
            map.put("teachingAssistant2Name", section.getTeachingAssistant2().getFullName());
        } else {
            map.put("teachingAssistant2UserId", null);
            map.put("teachingAssistant2Name", null);
        }
        map.put("createdAt", section.getCreatedAt());
        map.put("updatedAt", section.getUpdatedAt());
        return map;
    }

    private User getCurrentUser() {
        UserPrincipal principal = SecurityUtils.getCurrentUserPrincipal()
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
        return userService.getUserById(principal.getUser().getUserId())
                .orElse(principal.getUser());
    }
}
