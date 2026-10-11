package com.smartattendance.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "section_enrolments", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"student_id", "section_id"})
})
public class SectionEnrolments {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "enrolment_id")
    private Integer enrolmentId;

    @NotNull(message = "Student is required")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @NotNull(message = "Section is required")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "section_id", nullable = false)
    private Section section;

    @Column(name = "enrolment_date", nullable = false)
    private LocalDate enrolmentDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private EnrolmentStatus status = EnrolmentStatus.ENROLLED;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // Enums
    public enum EnrolmentStatus {
        ENROLLED, DROPPED, COMPLETED
    }

    // Constructors
    public SectionEnrolments() {
        this.enrolmentDate = LocalDate.now();
        this.createdAt = LocalDateTime.now();
    }

    public SectionEnrolments(Student student, Section section, LocalDate enrolmentDate, EnrolmentStatus status) {
        this();
        this.student = student;
        this.section = section;
        this.enrolmentDate = enrolmentDate;
        this.status = status;
    }

    // Pre-persist
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (enrolmentDate == null) {
            enrolmentDate = LocalDate.now();
        }
    }

    // Getters and Setters
    public Integer getEnrolmentId() {
        return enrolmentId;
    }

    public void setEnrolmentId(Integer enrolmentId) {
        this.enrolmentId = enrolmentId;
    }

    public Student getStudent() {
        return student;
    }

    public void setStudent(Student student) {
        this.student = student;
    }

    public Section getSection() {
        return section;
    }

    public void setSection(Section section) {
        this.section = section;
    }

    public LocalDate getEnrolmentDate() {
        return enrolmentDate;
    }

    public void setEnrolmentDate(LocalDate enrolmentDate) {
        this.enrolmentDate = enrolmentDate;
    }

    public EnrolmentStatus getStatus() {
        return status;
    }

    public void setStatus(EnrolmentStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public String toString() {
        return "SectionEnrolments{" +
                "enrolmentId=" + enrolmentId +
                ", studentId=" + (student != null ? student.getStudentId() : null) +
                ", sectionId=" + (section != null ? section.getSectionId() : null) +
                ", enrolmentDate=" + enrolmentDate +
                ", status=" + status +
                ", createdAt=" + createdAt +
                '}';
    }
}