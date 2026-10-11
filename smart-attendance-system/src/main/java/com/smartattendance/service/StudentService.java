package com.smartattendance.service;

import com.smartattendance.entity.SectionEnrolments;
import com.smartattendance.entity.Student;
import com.smartattendance.entity.TrainingData;
import com.smartattendance.repository.SectionEnrolmentsRepository;
import com.smartattendance.repository.StudentRepository;
import com.smartattendance.repository.TrainingDataRepository;
import com.smartattendance.service.recognition.FaceRecognitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class StudentService {

    private static final Logger log = LoggerFactory.getLogger(StudentService.class);

    public static final double MASK_CONFIRMATION_FLOOR = 0.05d;
    private static final double MASK_CONFIDENCE_THRESHOLD = 0.95d;

    private final StudentRepository studentRepository;
    private final TrainingDataRepository trainingDataRepository;
    private final SectionEnrolmentsRepository sectionEnrolmentsRepository;
    private final FaceRecognitionService faceRecognitionService;
    private final TrainingPhotoValidator trainingPhotoValidator;
    private final MaskAugmentationService maskAugmentationService;
    private final MaskDetectionService maskDetectionService;

    @Autowired
    public StudentService(StudentRepository studentRepository,
            TrainingDataRepository trainingDataRepository,
            SectionEnrolmentsRepository sectionEnrolmentsRepository,
            FaceRecognitionService faceRecognitionService,
            TrainingPhotoValidator trainingPhotoValidator,
            MaskAugmentationService maskAugmentationService,
            MaskDetectionService maskDetectionService) {
        this.studentRepository = studentRepository;
        this.trainingDataRepository = trainingDataRepository;
        this.sectionEnrolmentsRepository = sectionEnrolmentsRepository;
        this.faceRecognitionService = faceRecognitionService;
        this.trainingPhotoValidator = trainingPhotoValidator;
        this.maskAugmentationService = maskAugmentationService;
        this.maskDetectionService = maskDetectionService;
    }

    /**
     * Create a new student with training data (photos)
     */
    public StudentCreationResult createStudent(String studentId, String fullName, String email, String phoneNumber,
            List<MultipartFile> photos) {
        // Validate input
        validateStudentInput(studentId, fullName, email, phoneNumber);

        // Check if student ID already exists
        if (studentRepository.existsByStudentId(studentId)) {
            throw new IllegalArgumentException("Student ID already exists");
        }

        // Check if email already exists
        if (email != null && studentRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("Email already exists");
        }

        String normalizedPhone = normalizePhone(phoneNumber);

        // Create new student
        Student student = new Student(studentId, fullName, email);
        student.setPhoneNumber(normalizedPhone);

        // Save student first
        student = studentRepository.save(student);

        List<PhotoSaveResult> photoResults = new ArrayList<>();

        // Process and save training data if photos provided
        if (photos != null && !photos.isEmpty()) {
            for (MultipartFile photo : photos) {
                if (!photo.isEmpty()) {
                    try {
                        photoResults.add(saveTrainingData(student.getStudentId(), photo));
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to save photo: " + photo.getOriginalFilename(), e);
                    }
                }
            }
        }

        return new StudentCreationResult(student, photoResults);
    }

    /**
     * Get all students
     */
    @Transactional(readOnly = true)
    public List<Student> getAllStudents() {
        return studentRepository.findAll();
    }

    /**
     * Get student by ID
     */
    @Transactional(readOnly = true)
    public Optional<Student> getStudentById(String studentId) {
        return studentRepository.findByStudentId(studentId);
    }

    /**
     * Search students by name or ID
     */
    @Transactional(readOnly = true)
    public List<Student> searchStudents(String searchTerm) {
        if (searchTerm == null || searchTerm.trim().isEmpty()) {
            return getAllStudents();
        }
        return studentRepository.findByStudentIdOrFullNameContainingIgnoreCase(searchTerm.trim());
    }

    /**
     * Update student information and optionally change the student ID.
     */
    public Student updateStudent(String currentStudentId, String requestedStudentId, String fullName, String email,
            String phoneNumber) {
        Optional<Student> studentOpt = studentRepository.findByStudentId(currentStudentId);
        if (studentOpt.isEmpty()) {
            throw new IllegalArgumentException("Student not found");
        }

        Student existingStudent = studentOpt.get();

        String trimmedFullName = fullName != null ? fullName.trim() : null;
        String trimmedEmail = email != null ? email.trim() : null;
        if (trimmedEmail != null && trimmedEmail.isEmpty()) {
            trimmedEmail = null;
        }
        String trimmedPhone = normalizePhone(phoneNumber);

        String targetStudentId = (requestedStudentId != null && !requestedStudentId.trim().isEmpty())
                ? requestedStudentId.trim().toUpperCase()
                : existingStudent.getStudentId();

        if (trimmedFullName == null || trimmedFullName.isEmpty()) {
            throw new IllegalArgumentException("Full name is required");
        }

        validateStudentInput(targetStudentId, trimmedFullName, trimmedEmail, trimmedPhone);

        boolean idChanged = !existingStudent.getStudentId().equalsIgnoreCase(targetStudentId);

        if (trimmedEmail != null
                && studentRepository.existsByEmailAndStudentIdNot(trimmedEmail, existingStudent.getStudentId())) {
            throw new IllegalArgumentException("Email already exists");
        }

        if (idChanged && studentRepository.existsByStudentId(targetStudentId)) {
            throw new IllegalArgumentException("Student ID already exists");
        }

        existingStudent.setFullName(trimmedFullName);
        existingStudent.setEmail(trimmedEmail);
        existingStudent.setPhoneNumber(trimmedPhone);

        if (!idChanged) {
            existingStudent.setUpdatedAt(LocalDateTime.now());
            return studentRepository.save(existingStudent);
        }

        Student newStudent = new Student();
        newStudent.setStudentId(targetStudentId);
        newStudent.setFullName(trimmedFullName);
        newStudent.setEmail(trimmedEmail);
        newStudent.setPhoneNumber(trimmedPhone);
        newStudent.setEnrolmentDate(existingStudent.getEnrolmentDate());
        newStudent.setCreatedAt(existingStudent.getCreatedAt());
        newStudent.setUpdatedAt(LocalDateTime.now());

        Student savedNewStudent = studentRepository.save(newStudent);

        trainingDataRepository.updateStudentId(existingStudent.getStudentId(), targetStudentId);
        refreshRecognitionProfile(targetStudentId);

        List<SectionEnrolments> enrolments = sectionEnrolmentsRepository
                .findByStudentStudentId(existingStudent.getStudentId());
        for (SectionEnrolments enrolment : enrolments) {
            enrolment.setStudent(savedNewStudent);
        }
        if (!enrolments.isEmpty()) {
            sectionEnrolmentsRepository.saveAll(enrolments);
        }

        studentRepository.delete(existingStudent);
        studentRepository.flush();

        return studentRepository.findByStudentId(targetStudentId)
                .orElse(savedNewStudent);
    }

    /**
     * Add photos to existing student
     */
    public List<PhotoSaveResult> addPhotosToStudent(String studentId, List<MultipartFile> photos) {
        // Verify student exists
        if (!studentRepository.existsByStudentId(studentId)) {
            throw new IllegalArgumentException("Student not found");
        }

        if (photos == null || photos.isEmpty()) {
            return List.of();
        }

        List<PhotoSaveResult> results = photos.stream().filter(photo -> !photo.isEmpty()).map(photo -> {
            try {
                return saveTrainingData(studentId, photo);
            } catch (IOException e) {
                throw new RuntimeException("Failed to save photo: " + photo.getOriginalFilename(), e);
            }
        }).toList();

        return results;
    }

    /**
     * Delete student and all associated training data
     */
    public void deleteStudent(String studentId) {
        Optional<Student> studentOpt = studentRepository.findByStudentId(studentId);
        if (studentOpt.isEmpty()) {
            throw new IllegalArgumentException("Student not found");
        }

        // Delete all training data first (cascade delete should handle this, but being
        // explicit)
        trainingDataRepository.deleteByStudentId(studentId);

        // Delete student
        studentRepository.delete(studentOpt.get());
        refreshRecognitionProfile(studentId);
    }

    /**
     * Retrieve the first training image for a student encoded as a data URL.
     */
    @Transactional(readOnly = true)
    public Optional<String> getStudentProfilePhoto(String studentId) {
        return trainingDataRepository.findFirstByStudentIdOrderByCreatedAtAsc(studentId)
                .filter(td -> td.getImage() != null && td.getImage().length > 0)
                .map(td -> {
                    String mimeType = (td.getMimeType() != null && !td.getMimeType().isBlank())
                            ? td.getMimeType()
                            : "image/jpeg";
                    String base64 = Base64.getEncoder().encodeToString(td.getImage());
                    return "data:" + mimeType + ";base64," + base64;
                });
    }

    /**
     * Get training data for a student
     */
    @Transactional(readOnly = true)
    public List<TrainingData> getStudentPhotos(String studentId) {
        return trainingDataRepository.findByStudentIdAndMaskVariantFalse(studentId);
    }

    @Transactional(readOnly = true)
    public List<TrainingData> getMaskVariants(String studentId) {
        return trainingDataRepository.findByStudentIdAndMaskVariantTrue(studentId);
    }

    /**
     * Delete specific photo from student
     */
    public void deleteStudentPhoto(String studentId, Long photoId) {
        Optional<TrainingData> photoOpt = trainingDataRepository.findById(photoId);
        if (photoOpt.isEmpty()) {
            throw new IllegalArgumentException("Photo not found");
        }

        TrainingData photo = photoOpt.get();

        // Verify the photo belongs to the student
        if (!photo.getStudentId().equals(studentId)) {
            throw new IllegalArgumentException("Photo does not belong to the specified student");
        }

        if (!photo.isMaskVariant()) {
            trainingDataRepository.deleteBySourceTrainingId(photo.getId());
        }

        trainingDataRepository.delete(photo);
        refreshRecognitionProfile(studentId);
    }

    /**
     * Get student photo count
     */
    @Transactional(readOnly = true)
    public long getStudentPhotoCount(String studentId) {
        return trainingDataRepository.countByStudentIdAndMaskVariantFalse(studentId);
    }

    /**
     * Validate student input
     */
    private void validateStudentInput(String studentId, String fullName, String email, String phoneNumber) {
        if (studentId == null || studentId.trim().isEmpty()) {
            throw new IllegalArgumentException("Student ID is required");
        }
        if (fullName == null || fullName.trim().isEmpty()) {
            throw new IllegalArgumentException("Full name is required");
        }
        if (studentId.length() != 8 || !studentId.matches("\\d{8}")) {
            throw new IllegalArgumentException("Student ID must be 8 digits");
        }
        if (email != null && !email.matches(".+@.+\\..+")) {
            throw new IllegalArgumentException("Please enter a valid email address");
        }
        if (phoneNumber != null) {
            if (!phoneNumber.matches("^[89][0-9]{7}$")) {
                throw new IllegalArgumentException(
                        "Please enter a valid Singapore mobile number: 8 digits, starting with 8 or 9, with no spaces.");
            }
        }
    }

    private String normalizePhone(String phoneNumber) {
        if (phoneNumber == null)
            return null;
        String trimmed = phoneNumber.trim();
        return trimmed.isEmpty() ? null : trimmed; 
    }

    /**
     * Save training data (photo) for a student
     */
    private PhotoSaveResult saveTrainingData(String studentId, MultipartFile photo) throws IOException {
        // Get file data
        byte[] imageData = photo.getBytes();
        String filename = photo.getOriginalFilename();
        String mimeType = photo.getContentType();

        TrainingPhotoValidator.ValidationResult validationResult = trainingPhotoValidator.validate(imageData);
        if (!validationResult.valid()) {
            String reason = validationResult.errors().isEmpty() ? "Unsupported or low-quality photo"
                    : String.join("; ", validationResult.errors());
            throw new IllegalArgumentException(String.format("Photo %s rejected: %s",
                    filename != null ? filename : "(unnamed)", reason));
        }

        // Calculate checksum for duplicate detection
        String checksum = calculateChecksum(imageData);

        // Check for duplicates
        if (trainingDataRepository.findByStudentIdAndChecksum(studentId, checksum).isPresent()) {
            throw new IllegalArgumentException("Duplicate photo detected for student");
        }

        // Create training data entity
        TrainingData trainingData = new TrainingData(studentId, filename, mimeType, imageData, checksum);
        MaskDetectionService.MaskDetectionResult detection = maskDetectionService.evaluate(imageData);
        boolean hasRealMask = detection.masked() && detection.maskConfidence() >= MASK_CONFIDENCE_THRESHOLD;
        trainingData.setHasRealMask(hasRealMask);

        // Save to database
        TrainingData saved = trainingDataRepository.save(trainingData);
        trainingDataRepository.flush();

        if (!hasRealMask) {
            createMaskVariantIfMissing(saved);
        }

        refreshRecognitionProfile(studentId);
        return new PhotoSaveResult(saved, detection);
    }

    public TrainingData updateMaskStatus(String studentId, Long photoId, boolean hasRealMask) {
        TrainingData photo = trainingDataRepository.findById(photoId)
                .orElseThrow(() -> new IllegalArgumentException("Photo not found"));

        if (!photo.getStudentId().equals(studentId)) {
            throw new IllegalArgumentException("Photo does not belong to the specified student");
        }

        if (photo.isMaskVariant()) {
            throw new IllegalArgumentException("Mask variants inherit status from their source photo");
        }

        photo.setHasRealMask(hasRealMask);
        TrainingData saved = trainingDataRepository.save(photo);
        trainingDataRepository.flush();

        if (hasRealMask) {
            deleteMaskVariantsForSource(photo.getId());
        } else {
            createMaskVariantIfMissing(saved);
        }

        refreshRecognitionProfile(studentId);
        return saved;
    }

    private void createMaskVariantIfMissing(TrainingData source) {
        if (source == null || source.getId() == null) {
            return;
        }
        try {
            if (trainingDataRepository.existsBySourceTrainingId(source.getId())) {
                return;
            }
            maskAugmentationService.createMaskVariant(source).ifPresent(variant -> {
                try {
                    String maskChecksum = calculateChecksum(variant.imageBytes());
                    if (trainingDataRepository.findByStudentIdAndChecksum(source.getStudentId(), maskChecksum).isEmpty()) {
                        TrainingData maskData = new TrainingData(source.getStudentId(), variant.filename(),
                                "image/jpeg", variant.imageBytes(), maskChecksum);
                        maskData.setMaskVariant(true);
                        maskData.setSourceTrainingId(source.getId());
                        trainingDataRepository.save(maskData);
                    }
                } catch (Exception ex) {
                    log.debug("Unable to persist mask variant for training data {}: {}", source.getId(), ex.getMessage());
                }
            });
        } catch (Exception ex) {
            log.debug("Mask augmentation skipped for training data {}: {}", source.getId(), ex.getMessage());
        }
    }

    private void deleteMaskVariantsForSource(Long sourceId) {
        if (sourceId == null) {
            return;
        }
        try {
            trainingDataRepository.deleteBySourceTrainingId(sourceId);
        } catch (Exception ex) {
            log.debug("Failed to delete mask variants for {}: {}", sourceId, ex.getMessage());
        }
    }

    /**
     * Calculate SHA-256 checksum of image data
     */
    private String calculateChecksum(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data);
            StringBuilder hexString = new StringBuilder();

            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Check if student ID is available
     */
    @Transactional(readOnly = true)
    public boolean isStudentIdAvailable(String studentId) {
        return !studentRepository.existsByStudentId(studentId);
    }

    /**
     * Check if email is available
     */
    @Transactional(readOnly = true)
    public boolean isEmailAvailable(String email) {
        return email == null || !studentRepository.existsByEmail(email);
    }

    private void refreshRecognitionProfile(String studentId) {
        if (faceRecognitionService == null || studentId == null) {
            return;
        }
        try {
            faceRecognitionService.refreshStudentProfile(studentId);
        } catch (Exception ex) {
            log.warn("Failed to refresh recognition profile for {}: {}", studentId, ex.getMessage());
        }
    }

    public record PhotoSaveResult(TrainingData trainingData, MaskDetectionService.MaskDetectionResult detection) {}

    public record StudentCreationResult(Student student, List<PhotoSaveResult> photos) {}
}
