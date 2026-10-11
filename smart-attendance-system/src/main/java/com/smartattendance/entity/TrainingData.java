package com.smartattendance.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

@Entity
@Table(name = "training_data")
public class TrainingData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @NotBlank(message = "Student ID is required")
    @Size(max = 8, message = "Student ID cannot exceed 8 characters")
    @Column(name = "student_id", nullable = false, columnDefinition = "CHAR(8)")
    private String studentId;

    @Size(max = 255, message = "Filename cannot exceed 255 characters")
    @Column(name = "filename", length = 255)
    private String filename;

    @Size(max = 100, message = "MIME type cannot exceed 100 characters")
    @Column(name = "mime_type", length = 100)
    private String mimeType;

    @NotNull(message = "Image data is required")
    @Lob
    @Column(name = "image", nullable = false, columnDefinition = "MEDIUMBLOB")
    private byte[] image;

    @Size(max = 64, message = "Checksum cannot exceed 64 characters")
    @Column(name = "checksum", columnDefinition = "CHAR(64)")
    private String checksum;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "is_mask_variant", nullable = false)
    private boolean maskVariant = false;

    @Column(name = "source_training_id")
    private Long sourceTrainingId;

    @Column(name = "has_real_mask", nullable = false)
    private boolean hasRealMask = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", referencedColumnName = "student_id", insertable = false, updatable = false)
    private Student student;

    // Default constructor
    public TrainingData() {
        this.createdAt = LocalDateTime.now();
    }

    // Constructor for creating new training data
    public TrainingData(String studentId, String filename, String mimeType, byte[] image, String checksum) {
        this();
        this.studentId = studentId;
        this.filename = filename;
        this.mimeType = mimeType;
        this.image = image;
        this.checksum = checksum;
    }

    // Pre-persist method to set created_at
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getStudentId() {
        return studentId;
    }

    public void setStudentId(String studentId) {
        this.studentId = studentId;
    }

    public String getFilename() {
        return filename;
    }

    public void setFilename(String filename) {
        this.filename = filename;
    }

    public String getMimeType() {
        return mimeType;
    }

    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public byte[] getImage() {
        return image;
    }

    public void setImage(byte[] image) {
        this.image = image;
    }

    public String getChecksum() {
        return checksum;
    }

    public void setChecksum(String checksum) {
        this.checksum = checksum;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Student getStudent() {
        return student;
    }

    public void setStudent(Student student) {
        this.student = student;
    }

    public boolean isMaskVariant() {
        return maskVariant;
    }

    public void setMaskVariant(boolean maskVariant) {
        this.maskVariant = maskVariant;
    }

    public Long getSourceTrainingId() {
        return sourceTrainingId;
    }

    public void setSourceTrainingId(Long sourceTrainingId) {
        this.sourceTrainingId = sourceTrainingId;
    }

    public boolean isHasRealMask() {
        return hasRealMask;
    }

    public void setHasRealMask(boolean hasRealMask) {
        this.hasRealMask = hasRealMask;
    }

    @Override
    public String toString() {
        return "TrainingData{" +
                "id=" + id +
                ", studentId='" + studentId + '\'' +
                ", filename='" + filename + '\'' +
                ", mimeType='" + mimeType + '\'' +
                ", checksum='" + checksum + '\'' +
                ", createdAt=" + createdAt +
                ", maskVariant=" + maskVariant +
                ", sourceTrainingId=" + sourceTrainingId +
                ", hasRealMask=" + hasRealMask +
                '}';
    }
}
