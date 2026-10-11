package com.smartattendance.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
public class AttendanceAuditLogger {

    private static final Logger AUDIT_LOGGER = LoggerFactory.getLogger("attendance.audit");

    public void logStudentEnrolled(String studentId, Integer sectionId) {
        AUDIT_LOGGER.info("ENROLLED student={} section={} at={}", studentId, sectionId, LocalDateTime.now());
    }

    public void logRecognitionAutoMark(Integer sessionId,
                                       String studentId,
                                       BigDecimal confidence,
                                       String status,
                                       String method) {
        AUDIT_LOGGER.info("RECOGNIZED session={} student={} status={} method={} confidence={} at={}",
                sessionId, studentId, status, method, confidence, LocalDateTime.now());
    }

    public void logRecognitionSkipped(Integer sessionId, String reason) {
        AUDIT_LOGGER.warn("RECOGNITION_SKIPPED session={} reason={} at={}", sessionId, reason, LocalDateTime.now());
    }

    public void logManualOverride(Integer sessionId,
                                  String studentId,
                                  String previousStatus,
                                  String newStatus,
                                  Integer userId) {
        AUDIT_LOGGER.info("OVERRIDE session={} student={} from={} to={} user={} at={}",
                sessionId, studentId, previousStatus, newStatus, userId, LocalDateTime.now());
    }
}

