package com.smartattendance.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "session_roster", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"session_id", "student_id"})
})
public class SessionRoster {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "roster_id")
    private Integer rosterId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private Session session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @Column(name = "added_at", nullable = false, updatable = false)
    private LocalDateTime addedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "added_by")
    private User addedBy;

    public SessionRoster() {
        this.addedAt = LocalDateTime.now();
    }

    public SessionRoster(Session session, Student student, User addedBy) {
        this();
        this.session = session;
        this.student = student;
        this.addedBy = addedBy;
    }

    @PrePersist
    protected void onCreate() {
        if (addedAt == null) {
            addedAt = LocalDateTime.now();
        }
    }

    public Integer getRosterId() {
        return rosterId;
    }

    public void setRosterId(Integer rosterId) {
        this.rosterId = rosterId;
    }

    public Session getSession() {
        return session;
    }

    public void setSession(Session session) {
        this.session = session;
    }

    public Student getStudent() {
        return student;
    }

    public void setStudent(Student student) {
        this.student = student;
    }

    public LocalDateTime getAddedAt() {
        return addedAt;
    }

    public void setAddedAt(LocalDateTime addedAt) {
        this.addedAt = addedAt;
    }

    public User getAddedBy() {
        return addedBy;
    }

    public void setAddedBy(User addedBy) {
        this.addedBy = addedBy;
    }

    @Override
    public String toString() {
        return "SessionRoster{" +
                "rosterId=" + rosterId +
                ", sessionId=" + (session != null ? session.getSessionId() : null) +
                ", studentId=" + (student != null ? student.getStudentId() : null) +
                ", addedAt=" + addedAt +
                ", addedBy=" + (addedBy != null ? addedBy.getUserId() : null) +
                '}';
    }
}

