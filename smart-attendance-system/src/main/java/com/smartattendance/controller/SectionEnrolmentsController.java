package com.smartattendance.controller;

import com.smartattendance.entity.SectionEnrolments;
import com.smartattendance.service.SectionEnrolmentsService;
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
@RequestMapping("/api/section-enrolments")
@CrossOrigin(origins = "*")
@PreAuthorize("hasRole('ADMIN')")
public class SectionEnrolmentsController {

    private final SectionEnrolmentsService sectionEnrolmentsService;

    @Autowired
    public SectionEnrolmentsController(SectionEnrolmentsService sectionEnrolmentsService) {
        this.sectionEnrolmentsService = sectionEnrolmentsService;
    }

    /**
     * DTO for enrollment request
     */
    public static class EnrollmentRequest {
        private String studentId;
        private SectionEnrolments.EnrolmentStatus status;

        // Getters and setters
        public String getStudentId() { return studentId; }
        public void setStudentId(String studentId) { this.studentId = studentId; }

        public SectionEnrolments.EnrolmentStatus getStatus() { return status; }
        public void setStatus(SectionEnrolments.EnrolmentStatus status) { this.status = status; }
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
     * Get enrollments by section
     */
    @GetMapping("/section/{sectionId}")
    public ResponseEntity<ApiResponse> getEnrollmentsBySection(@PathVariable Integer sectionId) {
        try {
            List<SectionEnrolments> enrollments = sectionEnrolmentsService.getEnrollmentsBySection(sectionId);
            List<Map<String, Object>> enrollmentData = enrollments.stream().map(this::convertEnrollmentToMap).toList();

            return ResponseEntity.ok(new ApiResponse(true, "Enrollments retrieved successfully", enrollmentData));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error retrieving enrollments: " + e.getMessage()));
        }
    }

    /**
     * Get enrollments by student
     */
    @GetMapping("/student/{studentId}")
    public ResponseEntity<ApiResponse> getEnrollmentsByStudent(@PathVariable String studentId) {
        try {
            List<SectionEnrolments> enrollments = sectionEnrolmentsService.getEnrollmentsByStudent(studentId);
            List<Map<String, Object>> enrollmentData = enrollments.stream().map(this::convertEnrollmentToMap).toList();

            return ResponseEntity.ok(new ApiResponse(true, "Enrollments retrieved successfully", enrollmentData));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error retrieving enrollments: " + e.getMessage()));
        }
    }

    /**
     * Get specific enrollment by enrollmentId
     */
    @GetMapping("/{enrollmentId}")
    public ResponseEntity<ApiResponse> getEnrollmentById(@PathVariable Integer enrollmentId) {
        try {
            Optional<SectionEnrolments> enrollmentOpt = sectionEnrolmentsService.getEnrollmentById(enrollmentId);
            if (enrollmentOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            Map<String, Object> enrollmentData = convertEnrollmentToMap(enrollmentOpt.get());
            return ResponseEntity.ok(new ApiResponse(true, "Enrollment retrieved successfully", enrollmentData));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error retrieving enrollment: " + e.getMessage()));
        }
    }

    /**
     * Get specific enrollment
     */
    @GetMapping("/section/{sectionId}/student/{studentId}")
    public ResponseEntity<ApiResponse> getEnrollment(@PathVariable Integer sectionId, @PathVariable String studentId) {
        try {
            Optional<SectionEnrolments> enrollmentOpt = sectionEnrolmentsService.getEnrollment(sectionId, studentId);
            if (enrollmentOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            Map<String, Object> enrollmentData = convertEnrollmentToMap(enrollmentOpt.get());
            return ResponseEntity.ok(new ApiResponse(true, "Enrollment retrieved successfully", enrollmentData));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error retrieving enrollment: " + e.getMessage()));
        }
    }

    /**
     * Enroll student in section
     */
    @PostMapping("/section/{sectionId}/student/{studentId}")
    public ResponseEntity<ApiResponse> enrollStudent(@PathVariable Integer sectionId,
                                                    @PathVariable String studentId,
                                                    @RequestBody(required = false) EnrollmentRequest request) {
        try {
            SectionEnrolments.EnrolmentStatus status = SectionEnrolments.EnrolmentStatus.ENROLLED;
            if (request != null && request.getStatus() != null) {
                status = request.getStatus();
            }

            SectionEnrolments enrollment = sectionEnrolmentsService.enrollStudent(sectionId, studentId, status);

            Map<String, Object> enrollmentData = convertEnrollmentToMap(enrollment);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(new ApiResponse(true, "Student enrolled successfully", enrollmentData));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error enrolling student: " + e.getMessage()));
        }
    }

    /**
     * Update enrollment status
     */
    @PutMapping("/section/{sectionId}/student/{studentId}")
    public ResponseEntity<ApiResponse> updateEnrollmentStatus(@PathVariable Integer sectionId,
                                                              @PathVariable String studentId,
                                                              @Valid @RequestBody EnrollmentRequest request) {
        System.out.println("DEBUG: UpdateEnrollmentStatus called with sectionId=" + sectionId + ", studentId=" + studentId + ", status=" + request.getStatus());
        try {
            if (request.getStatus() == null) {
                System.out.println("DEBUG: Status is null, returning bad request");
                return ResponseEntity.badRequest()
                        .body(new ApiResponse(false, "Status is required"));
            }

            SectionEnrolments enrollment = sectionEnrolmentsService.updateEnrollmentStatus(sectionId, studentId, request.getStatus());
            System.out.println("DEBUG: Enrollment updated successfully, enrollmentId=" + enrollment.getEnrolmentId());

            Map<String, Object> enrollmentData = convertEnrollmentToMap(enrollment);
            return ResponseEntity.ok(new ApiResponse(true, "Enrollment updated successfully", enrollmentData));

        } catch (IllegalArgumentException e) {
            System.out.println("DEBUG: IllegalArgumentException: " + e.getMessage());
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, e.getMessage()));
        } catch (Exception e) {
            System.out.println("DEBUG: Exception in updateEnrollmentStatus: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error updating enrollment: " + e.getMessage()));
        }
    }

    /**
     * Remove student from section
     */
    @DeleteMapping("/section/{sectionId}/student/{studentId}")
    public ResponseEntity<ApiResponse> removeStudentFromSection(@PathVariable Integer sectionId,
                                                              @PathVariable String studentId) {
        try {
            sectionEnrolmentsService.removeStudentFromSection(sectionId, studentId);
            return ResponseEntity.ok(new ApiResponse(true, "Student removed from section successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error removing student: " + e.getMessage()));
        }
    }

    /**
     * Helper method to convert Enrollment entity to Map
     */
    private Map<String, Object> convertEnrollmentToMap(SectionEnrolments enrollment) {
        Map<String, Object> map = new HashMap<>();
        map.put("enrolmentId", enrollment.getEnrolmentId());
        map.put("studentId", enrollment.getStudent().getStudentId());
        map.put("studentName", enrollment.getStudent().getFullName());
        map.put("sectionId", enrollment.getSection().getSectionId());
        map.put("courseId", enrollment.getSection().getCourse().getCourseId());
        map.put("courseCode", enrollment.getSection().getCourse().getCourseCode());
        map.put("sectionCode", enrollment.getSection().getSectionCode());
        map.put("enrolmentDate", enrollment.getEnrolmentDate());
        map.put("status", enrollment.getStatus());
        map.put("createdAt", enrollment.getCreatedAt());
        return map;
    }
}
