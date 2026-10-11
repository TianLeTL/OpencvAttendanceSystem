package com.smartattendance.controller;

import com.smartattendance.entity.Student;
import com.smartattendance.entity.TrainingData;
import com.smartattendance.service.MaskDetectionService;
import com.smartattendance.service.StudentService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/students")
@CrossOrigin(origins = "*") // Allow frontend to call this API
@PreAuthorize("hasRole('ADMIN')")
public class StudentController {

    private final StudentService studentService;

    @Autowired
    public StudentController(StudentService studentService) {
        this.studentService = studentService;
    }

    /**
     * DTO for student creation request
     */
    public static class CreateStudentRequest {
        private String studentId;
        private String fullName;
        private String email;
        private String phoneNumber;

        // Default constructor
        public CreateStudentRequest() {}

        // Getters and setters
        public String getStudentId() {
            return studentId;
        }

        public void setStudentId(String studentId) {
            this.studentId = studentId;
        }

        public String getFullName() {
            return fullName;
        }

        public void setFullName(String fullName) {
            this.fullName = fullName;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPhoneNumber() {
            return phoneNumber;
        }

        public void setPhoneNumber(String phoneNumber) {
            this.phoneNumber = phoneNumber;
        }
    }

    /**
     * DTO for student update request
     */
    public static class UpdateStudentRequest {
        private String studentId;
        private String fullName;
        private String email;
        private String phoneNumber;

        // Default constructor
        public UpdateStudentRequest() {}

        // Getters and setters
        public String getStudentId() {
            return studentId;
        }

        public void setStudentId(String studentId) {
            this.studentId = studentId;
        }

        public String getFullName() {
            return fullName;
        }

        public void setFullName(String fullName) {
            this.fullName = fullName;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPhoneNumber() {
            return phoneNumber;
        }

        public void setPhoneNumber(String phoneNumber) {
            this.phoneNumber = phoneNumber;
        }
    }

    /**
     * DTO for mask status updates
     */
    public static class MaskStatusRequest {
        private boolean hasRealMask;

        public boolean isHasRealMask() {
            return hasRealMask;
        }

        public void setHasRealMask(boolean hasRealMask) {
            this.hasRealMask = hasRealMask;
        }
    }

    /**
     * DTO for API response
     */
    public static class ApiResponse {
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
        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public Object getData() {
            return data;
        }

        public void setData(Object data) {
            this.data = data;
        }
    }

    /**
     * Get all students with optional search
     */
    @GetMapping
    public ResponseEntity<ApiResponse> getAllStudents(@RequestParam(required = false) String search) {
        try {
            List<Student> students = studentService.searchStudents(search);

            // Convert to response format
            List<Map<String, Object>> studentData = students.stream().map(this::convertStudentToMap).toList();

            return ResponseEntity.ok(new ApiResponse(true, "Students retrieved successfully", studentData));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse(false, "Error retrieving students: " + e.getMessage()));
        }
    }

    /**
     * Get student by ID
     */
    @GetMapping("/{studentId}")
    public ResponseEntity<ApiResponse> getStudentById(@PathVariable String studentId) {
        try {
            Optional<Student> studentOpt = studentService.getStudentById(studentId);

            if (studentOpt.isEmpty()) {
                return ResponseEntity.notFound()
                    .build();
            }

            Student student = studentOpt.get();
            Map<String, Object> studentData = convertStudentToMap(student);

            return ResponseEntity.ok(new ApiResponse(true, "Student retrieved successfully", studentData));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse(false, "Error retrieving student: " + e.getMessage()));
        }
    }

    /**
     * Create new student (multipart form data with optional photos)
     */
    @PostMapping
    public ResponseEntity<ApiResponse> createStudent(
            @RequestParam String studentId,
            @RequestParam String fullName,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String phoneNumber,
            @RequestParam(required = false) List<MultipartFile> photos) {
        try {
            // Validate required fields
            if (studentId == null || studentId.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "Student ID is required"));
            }

            if (fullName == null || fullName.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "Full name is required"));
            }

            var creationResult = studentService.createStudent(
                studentId.trim(),
                fullName.trim(),
                email != null ? email.trim() : null,
                phoneNumber != null ? phoneNumber.trim() : null,
                photos
            );

            Map<String, Object> studentData = convertStudentToMap(creationResult.student());
            if (!creationResult.photos().isEmpty()) {
                studentData.put("photos", convertPhotoResults(creationResult.photos(), true));
            }

            return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse(true, "Student created successfully", studentData));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(new ApiResponse(false, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse(false, "Error creating student: " + e.getMessage()));
        }
    }

    /**
     * Create new student (multipart form data with photos)
     */
    @PostMapping(value = "/with-photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse> createStudentWithPhotos(
            @RequestParam String studentId,
            @RequestParam String fullName,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String phoneNumber,
            @RequestParam(required = false) List<MultipartFile> photos) {
        try {
            // Validate required fields
            if (studentId == null || studentId.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "Student ID is required"));
            }

            if (fullName == null || fullName.trim().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "Full name is required"));
            }

            var creationResult = studentService.createStudent(studentId.trim(), fullName.trim(),
                email != null ? email.trim() : null,
                phoneNumber != null ? phoneNumber.trim() : null,
                photos);

            Map<String, Object> studentData = convertStudentToMap(creationResult.student());
            if (!creationResult.photos().isEmpty()) {
                studentData.put("photos", convertPhotoResults(creationResult.photos(), true));
            }

            return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse(true, "Student created successfully", studentData));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(new ApiResponse(false, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse(false, "Error creating student: " + e.getMessage()));
        }
    }

    /**
     * Update student
     */
    @PutMapping("/{studentId}")
    public ResponseEntity<ApiResponse> updateStudent(
            @PathVariable String studentId,
            @Valid @RequestBody UpdateStudentRequest request) {
        try {
            // Validate required fields
            if (request.getFullName() == null || request.getFullName().trim().isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "Full name is required"));
            }

            String requestedStudentId = request.getStudentId();
            String trimmedStudentId = requestedStudentId != null ? requestedStudentId.trim() : null;
            String trimmedEmail = request.getEmail() != null ? request.getEmail().trim() : null;
            if (trimmedEmail != null && trimmedEmail.isEmpty()) {
                trimmedEmail = null;
            }
            String trimmedPhone = request.getPhoneNumber() != null ? request.getPhoneNumber().trim() : null;
            if (trimmedPhone != null && trimmedPhone.isEmpty()) {
                trimmedPhone = null;
            }

            Student student = studentService.updateStudent(
                studentId,
                (trimmedStudentId != null && !trimmedStudentId.isEmpty()) ? trimmedStudentId : null,
                request.getFullName().trim(),
                trimmedEmail,
                trimmedPhone
            );

            Map<String, Object> studentData = convertStudentToMap(student);

            return ResponseEntity.ok(new ApiResponse(true, "Student updated successfully", studentData));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(new ApiResponse(false, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse(false, "Error updating student: " + e.getMessage()));
        }
    }

    /**
     * Delete student
     */
    @DeleteMapping("/{studentId}")
    public ResponseEntity<ApiResponse> deleteStudent(@PathVariable String studentId) {
        try {
            studentService.deleteStudent(studentId);
            return ResponseEntity.ok(new ApiResponse(true, "Student deleted successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(new ApiResponse(false, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse(false, "Error deleting student: " + e.getMessage()));
        }
    }

    /**
     * Add photos to student
     */
    @PostMapping(value = "/{studentId}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse> addPhotosToStudent(
            @PathVariable String studentId,
            @RequestParam List<MultipartFile> photos) {
        try {
            if (photos == null || photos.isEmpty()) {
                return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, "No photos provided"));
            }

            var results = studentService.addPhotosToStudent(studentId, photos);

            long photoCount = studentService.getStudentPhotoCount(studentId);

            List<Map<String, Object>> newPhotos = convertPhotoResults(results, true);

            Map<String, Object> data = new HashMap<>();
            data.put("studentId", studentId);
            data.put("photoCount", photoCount);
            data.put("photos", newPhotos);

            return ResponseEntity.ok(new ApiResponse(true, "Photos added successfully", data));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(new ApiResponse(false, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse(false, "Error adding photos: " + e.getMessage()));
        }
    }

    /**
     * Delete specific photo from student
     */
    @DeleteMapping("/{studentId}/photos/{photoId}")
    public ResponseEntity<ApiResponse> deleteStudentPhoto(
            @PathVariable String studentId,
            @PathVariable Long photoId) {
        try {
            studentService.deleteStudentPhoto(studentId, photoId);

            // Get updated photo count
            long photoCount = studentService.getStudentPhotoCount(studentId);

            Map<String, Object> data = new HashMap<>();
            data.put("studentId", studentId);
            data.put("photoCount", photoCount);

            return ResponseEntity.ok(new ApiResponse(true, "Photo deleted successfully", data));

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(new ApiResponse(false, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse(false, "Error deleting photo: " + e.getMessage()));
        }
    }

    /**
     * Update mask status for a training photo
     */
    @PatchMapping("/{studentId}/photos/{photoId}/mask-status")
    public ResponseEntity<ApiResponse> updateMaskStatus(
            @PathVariable String studentId,
            @PathVariable Long photoId,
            @RequestBody(required = false) MaskStatusRequest request) {
        try {
            if (request == null) {
                return ResponseEntity.badRequest()
                        .body(new ApiResponse(false, "Request body is required"));
            }
            TrainingData updated = studentService.updateMaskStatus(studentId, photoId, request.isHasRealMask());
            Map<String, Object> data = new HashMap<>();
            data.put("studentId", studentId);
            data.put("photoId", updated.getId());
            data.put("hasRealMask", updated.isHasRealMask());
            return ResponseEntity.ok(new ApiResponse(true, "Mask status updated successfully", data));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                .body(new ApiResponse(false, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse(false, "Error updating mask status: " + e.getMessage()));
        }
    }

    /**
     * Get student with photos for detailed view
     */
    @GetMapping("/{studentId}/detail")
    public ResponseEntity<ApiResponse> getStudentWithPhotos(@PathVariable String studentId) {
        try {
            Optional<Student> studentOpt = studentService.getStudentById(studentId);

            if (studentOpt.isEmpty()) {
                return ResponseEntity.notFound().build();
            }

            Student student = studentOpt.get();

            // Get student's photos
            List<TrainingData> photos = studentService.getStudentPhotos(studentId);
            List<TrainingData> maskPhotos = studentService.getMaskVariants(studentId);

            // Convert photos to response format with base64 encoded images for frontend
            List<Map<String, Object>> photoData = photos.stream().map(photo -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", photo.getId());
                map.put("filename", photo.getFilename());
                map.put("mimeType", photo.getMimeType());
                map.put("checksum", photo.getChecksum());
                map.put("createdAt", photo.getCreatedAt());
                map.put("maskVariant", false);
                map.put("sourceTrainingId", photo.getSourceTrainingId());
                map.put("hasRealMask", photo.isHasRealMask());

                // Convert image bytes to base64 for frontend display
                if (photo.getImage() != null) {
                    String base64Image = java.util.Base64.getEncoder().encodeToString(photo.getImage());
                    map.put("imageData", "data:" + photo.getMimeType() + ";base64," + base64Image);
                }

                return map;
            }).toList();

            List<Map<String, Object>> maskPhotoData = maskPhotos.stream().map(photo -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", photo.getId());
                map.put("filename", photo.getFilename());
                map.put("mimeType", photo.getMimeType());
                map.put("checksum", photo.getChecksum());
                map.put("createdAt", photo.getCreatedAt());
                map.put("maskVariant", true);
                map.put("sourceTrainingId", photo.getSourceTrainingId());
                map.put("hasRealMask", false);
                if (photo.getImage() != null) {
                    String base64Image = java.util.Base64.getEncoder().encodeToString(photo.getImage());
                    map.put("imageData", "data:" + photo.getMimeType() + ";base64," + base64Image);
                }
                return map;
            }).toList();

            // Combine student data with photos
            Map<String, Object> studentDetail = convertStudentToMap(student);
            studentDetail.put("photos", photoData);
            studentDetail.put("maskPhotos", maskPhotoData);

            return ResponseEntity.ok(new ApiResponse(true, "Student detail retrieved successfully", studentDetail));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse(false, "Error retrieving student detail: " + e.getMessage()));
        }
    }

    /**
     * Get student's photos
     */
    @GetMapping("/{studentId}/photos")
    public ResponseEntity<ApiResponse> getStudentPhotos(@PathVariable String studentId) {
        try {
            List<TrainingData> photos = studentService.getStudentPhotos(studentId);

            // Convert to response format (without image data for performance)
            List<Map<String, Object>> photoData = photos.stream().map(photo -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", photo.getId());
                map.put("filename", photo.getFilename());
                map.put("mimeType", photo.getMimeType());
                map.put("checksum", photo.getChecksum());
                map.put("createdAt", photo.getCreatedAt());
                map.put("maskVariant", false);
                map.put("sourceTrainingId", photo.getSourceTrainingId());
                map.put("hasRealMask", photo.isHasRealMask());
                map.put("imageSize", photo.getImage() != null ? photo.getImage().length : 0);
                return map;
            }).toList();

            return ResponseEntity.ok(new ApiResponse(true, "Photos retrieved successfully", photoData));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse(false, "Error retrieving photos: " + e.getMessage()));
        }
    }

    /**
     * Check student ID availability
     */
    @GetMapping("/check-student-id")
    public ResponseEntity<ApiResponse> checkStudentId(@RequestParam String studentId) {
        try {
            boolean available = studentService.isStudentIdAvailable(studentId);
            Map<String, Object> data = new HashMap<>();
            data.put("studentId", studentId);
            data.put("available", available);

            return ResponseEntity.ok(new ApiResponse(true, "Student ID check completed", data));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse(false, "Error checking student ID availability"));
        }
    }

    /**
     * Check email availability
     */
    @GetMapping("/check-email")
    public ResponseEntity<ApiResponse> checkEmail(@RequestParam String email) {
        try {
            boolean available = studentService.isEmailAvailable(email);
            Map<String, Object> data = new HashMap<>();
            data.put("email", email);
            data.put("available", available);

            return ResponseEntity.ok(new ApiResponse(true, "Email check completed", data));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse(false, "Error checking email availability"));
        }
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<ApiResponse> health() {
        return ResponseEntity.ok(new ApiResponse(true, "Student service is running"));
    }

    /**
     * Helper method to convert Student entity to Map for API response
     */
    private Map<String, Object> convertStudentToMap(Student student) {
        Map<String, Object> map = new HashMap<>();
        map.put("studentId", student.getStudentId());
        map.put("fullName", student.getFullName());
        map.put("email", student.getEmail());
        map.put("phoneNumber", student.getPhoneNumber());
        map.put("enrolmentDate", student.getEnrolmentDate());
        map.put("createdAt", student.getCreatedAt());
        map.put("updatedAt", student.getUpdatedAt());
        map.put("photoCount", student.getPhotoCount());
        map.put("profilePhoto", studentService.getStudentProfilePhoto(student.getStudentId()).orElse(null));
        return map;
    }

    private List<Map<String, Object>> convertPhotoResults(List<StudentService.PhotoSaveResult> results, boolean includeImageData) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        return results.stream()
                .map(res -> convertPhotoResult(res, includeImageData))
                .toList();
    }

    private Map<String, Object> convertPhotoResult(StudentService.PhotoSaveResult result, boolean includeImageData) {
        TrainingData photo = result.trainingData();
        MaskDetectionService.MaskDetectionResult detection = result.detection();
        Map<String, Object> map = new HashMap<>();
        boolean autoMaskSuggestion = detection.masked()
                && detection.maskConfidence() >= StudentService.MASK_CONFIRMATION_FLOOR;
        map.put("id", photo.getId());
        map.put("filename", photo.getFilename());
        map.put("mimeType", photo.getMimeType());
        map.put("hasRealMask", photo.isHasRealMask());
        map.put("autoMaskSuggestion", autoMaskSuggestion);
        map.put("maskConfidence", detection.maskConfidence());
        map.put("createdAt", photo.getCreatedAt());
        if (includeImageData && photo.getImage() != null) {
            String base64Image = java.util.Base64.getEncoder().encodeToString(photo.getImage());
            map.put("imageData", "data:" + photo.getMimeType() + ";base64," + base64Image);
        }
        return map;
    }
}
