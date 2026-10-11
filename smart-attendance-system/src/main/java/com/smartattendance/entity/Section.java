package com.smartattendance.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

@Entity
@Table(name = "sections", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"course_id", "section_code"})
})
public class Section {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "section_id")
    private Integer sectionId;

    @NotNull(message = "Course is required")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @NotBlank(message = "Section code is required")
    @Size(max = 10, message = "Section code cannot exceed 10 characters")
    @Column(name = "section_code", nullable = false, length = 10)
    private String sectionCode;

    @Size(max = 100, message = "Instructor name cannot exceed 100 characters")
    @Column(name = "instructor_name", length = 100)
    private String instructorName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "professor_id")
    private User professor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teaching_assistant1_id")
    private User teachingAssistant1;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "teaching_assistant2_id")
    private User teachingAssistant2;

    @Column(name = "max_capacity", nullable = false)
    private Integer maxCapacity = 50;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    // Constructors
    public Section() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public Section(Course course, String sectionCode, String instructorName, Integer maxCapacity) {
        this();
        this.course = course;
        this.sectionCode = sectionCode;
        this.instructorName = instructorName;
        this.maxCapacity = maxCapacity;
    }

    // Pre-persist and Pre-update
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Integer getSectionId() {
        return sectionId;
    }

    public void setSectionId(Integer sectionId) {
        this.sectionId = sectionId;
    }

    public Course getCourse() {
        return course;
    }

    public void setCourse(Course course) {
        this.course = course;
    }

    public String getSectionCode() {
        return sectionCode;
    }

    public void setSectionCode(String sectionCode) {
        this.sectionCode = sectionCode;
    }

    public String getInstructorName() {
        return instructorName;
    }

    public void setInstructorName(String instructorName) {
        this.instructorName = instructorName;
    }

    public Integer getMaxCapacity() {
        return maxCapacity;
    }

    public void setMaxCapacity(Integer maxCapacity) {
        this.maxCapacity = maxCapacity;
    }

    public User getProfessor() {
        return professor;
    }

    public void setProfessor(User professor) {
        this.professor = professor;
    }

    public User getTeachingAssistant1() {
        return teachingAssistant1;
    }

    public void setTeachingAssistant1(User teachingAssistant1) {
        this.teachingAssistant1 = teachingAssistant1;
    }

    public User getTeachingAssistant2() {
        return teachingAssistant2;
    }

    public void setTeachingAssistant2(User teachingAssistant2) {
        this.teachingAssistant2 = teachingAssistant2;
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

    @Override
    public String toString() {
        return "Section{" +
                "sectionId=" + sectionId +
                ", courseId=" + (course != null ? course.getCourseId() : null) +
                ", sectionCode='" + sectionCode + '\'' +
                ", instructorName='" + instructorName + '\'' +
                ", professorId=" + (professor != null ? professor.getUserId() : null) +
                ", teachingAssistant1Id=" + (teachingAssistant1 != null ? teachingAssistant1.getUserId() : null) +
                ", teachingAssistant2Id=" + (teachingAssistant2 != null ? teachingAssistant2.getUserId() : null) +
                ", maxCapacity=" + maxCapacity +
                ", createdAt=" + createdAt +
                ", updatedAt=" + updatedAt +
                '}';
    }
}
