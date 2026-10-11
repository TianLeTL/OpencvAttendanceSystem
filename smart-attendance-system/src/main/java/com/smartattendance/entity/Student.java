package com.smartattendance.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "students")
public class Student {

    @Id
    @NotBlank(message = "Student ID is required")
    @Size(min = 8, max = 8, message = "Student ID must have 8 digits")
    @Pattern(regexp = "\\d{8}", message = "Student ID must have 8 digits")
    @Column(name = "student_id", nullable = false, unique = true, columnDefinition = "CHAR(8)")
    private String studentId;

    @NotBlank(message = "Full name is required")
    @Size(max = 100, message = "Full name cannot exceed 100 characters")
    @Column(name = "full_name", nullable = false, length = 100)
    private String fullName;

    @Email(message = "Email should be valid")
    @Size(max = 100, message = "Email cannot exceed 100 characters")
    @Column(name = "email", length = 100)
    private String email;

    @Size(min = 8, max = 8, message = "Phone number cannot exceed 8 characters")
    @Pattern(regexp = "^[89]\\d{7}$|^$", message = "Phone number must start with 8 or 9 and be exactly 8 digits")
    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "enrolment_date", nullable = false)
    private LocalDate enrolmentDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "student", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<TrainingData> trainingData = new ArrayList<>();

    // Default constructor
    public Student() {
        this.enrolmentDate = LocalDate.now();
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    // Constructor for creating new student
    public Student(String studentId, String fullName, String email) {
        this();
        this.studentId = studentId;
        this.fullName = fullName;
        this.email = email;
    }

    // Pre-persist method to set created_at
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
        if (enrolmentDate == null) {
            enrolmentDate = LocalDate.now();
        }
    }

    // Pre-update method to set updated_at
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
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

    public LocalDate getEnrolmentDate() {
        return enrolmentDate;
    }

    public void setEnrolmentDate(LocalDate enrolmentDate) {
        this.enrolmentDate = enrolmentDate;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public List<TrainingData> getTrainingData() {
        return trainingData;
    }

    public void setTrainingData(List<TrainingData> trainingData) {
        this.trainingData = trainingData;
    }

    // Helper method to get photo count
    public int getPhotoCount() {
        return trainingData != null ? trainingData.size() : 0;
    }

    @Override
    public String toString() {
        return "Student{" +
                "studentId='" + studentId + '\'' +
                ", fullName='" + fullName + '\'' +
                ", email='" + email + '\'' +
                ", phoneNumber='" + phoneNumber + '\'' +
                ", enrolmentDate=" + enrolmentDate +
                ", createdAt=" + createdAt +
                ", photoCount=" + getPhotoCount() +
                '}';
    }
}
