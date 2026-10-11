package com.smartattendance.service.recognition;

import com.smartattendance.entity.AttendanceRecord;
import com.smartattendance.entity.AttendanceRecord.AttendanceStatus;
import com.smartattendance.entity.AttendanceRecord.MarkingMethod;
import com.smartattendance.entity.Session;
import com.smartattendance.entity.Session.SessionStatus;
import com.smartattendance.entity.SessionRoster;
import com.smartattendance.entity.Student;
import com.smartattendance.entity.User;
import com.smartattendance.repository.AttendanceRecordRepository;
import com.smartattendance.repository.SessionRepository;
import com.smartattendance.repository.SessionRosterRepository;
import com.smartattendance.repository.UserRepository;
import com.smartattendance.service.AttendanceAuditLogger;
import com.smartattendance.service.SystemSettingsService;
import com.smartattendance.service.recognition.FaceRecognitionService.DetectedFace;
import com.smartattendance.service.recognition.FaceRecognitionService.MatchCandidate;
import com.smartattendance.service.recognition.FaceRecognitionService.BoundingBox;
import com.smartattendance.service.recognition.tracking.FaceTrackComponent;
import com.smartattendance.service.recognition.tracking.MultiFaceTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Applies recognition results to attendance records, enforcing roster membership, cooldowns,
 * and auto-marking rules. Emits SSE events for UI consumption.
 */
@Service
@Transactional
public class AutomaticAttendanceService {

    private static final Logger log = LoggerFactory.getLogger(AutomaticAttendanceService.class);

    private static final double REVIEW_LOWER_BOUND = 50.0;

    private final SessionRepository sessionRepository;
    private final SessionRosterRepository sessionRosterRepository;
    private final AttendanceRecordRepository attendanceRecordRepository;
    private final SystemSettingsService systemSettingsService;
    private final RecognitionEventService recognitionEventService;
    private final AttendanceAuditLogger attendanceAuditLogger;
    private final PendingRecognitionStore pendingRecognitionStore;
    private final UserRepository userRepository;

    private static final long COOLDOWN_SECONDS = 30;
    private final MultiFaceTracker multiFaceTracker = new MultiFaceTracker();
    private final Map<Integer, Map<String, LocalDateTime>> lastSeenCache = new ConcurrentHashMap<>();
    private final Map<Integer, Map<String, Student>> rosterCache = new ConcurrentHashMap<>();

    public AutomaticAttendanceService(SessionRepository sessionRepository,
                                      SessionRosterRepository sessionRosterRepository,
                                      AttendanceRecordRepository attendanceRecordRepository,
                                      SystemSettingsService systemSettingsService,
                                      RecognitionEventService recognitionEventService,
                                      AttendanceAuditLogger attendanceAuditLogger,
                                      PendingRecognitionStore pendingRecognitionStore,
                                      UserRepository userRepository) {
        this.sessionRepository = sessionRepository;
        this.sessionRosterRepository = sessionRosterRepository;
        this.attendanceRecordRepository = attendanceRecordRepository;
        this.systemSettingsService = systemSettingsService;
        this.recognitionEventService = recognitionEventService;
        this.attendanceAuditLogger = attendanceAuditLogger;
        this.pendingRecognitionStore = pendingRecognitionStore;
        this.userRepository = userRepository;
    }

    public void clearSessionCaches(Integer sessionId) {
        lastSeenCache.remove(sessionId);
        rosterCache.remove(sessionId);
    }

    public void applyFrame(Integer sessionId,
                           FaceRecognitionService.FrameAnalysisResult frameAnalysis) {
        if (frameAnalysis == null || frameAnalysis.isEmpty()) {
            recognitionEventService.publish(sessionId, "recognition.idle", Map.of(
                    "timestamp", LocalDateTime.now().toString()
            ));
            return;
        }

        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));

        if (session.getStatus() != SessionStatus.OPEN) {
            throw new IllegalStateException("Session is not open");
        }

        Map<String, Student> roster = loadRoster(sessionId);
        if (roster.isEmpty()) {
            recognitionEventService.publish(sessionId, "recognition.empty-roster", Map.of(
                    "timestamp", LocalDateTime.now().toString()
            ));
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        double threshold = systemSettingsService.getRecognitionThreshold().doubleValue();
        double autoMarkThreshold = Math.max(REVIEW_LOWER_BOUND, threshold);

        List<FaceTrackComponent> components = multiFaceTracker.groupFaces(frameAnalysis);
        for (FaceTrackComponent component : components) {
            DetectedFace face = component.representative();
            List<MatchCandidate> rosterCandidates = face.candidates().stream()
                    .filter(candidate -> roster.containsKey(candidate.studentId()))
                    .toList();

            if (rosterCandidates.isEmpty()) {
                publishSkip(sessionId, "NOT_IN_SESSION", face.box(), frameAnalysis, null, face.detectionScore());
                continue;
            }

            DetectedFace rosterFace = new DetectedFace(face.box(), face.detectionScore(), face.eyeOpenness(), rosterCandidates);
            MatchCandidate match = rosterCandidates.get(0);
            Student rosterStudent = roster.get(match.studentId());
            String overlayLabel = rosterStudent != null
                    ? rosterStudent.getFullName() + " (" + formatConfidence(match.confidence()) + ")"
                    : match.displayName() + " (" + formatConfidence(match.confidence()) + ")";

            boolean hasPending = pendingRecognitionStore.hasPendingForStudent(sessionId, match.studentId());

            if (!hasPending && isWithinCooldown(sessionId, match.studentId(), now)) {
                publishSkip(sessionId, "COOLDOWN", rosterFace.box(), frameAnalysis, match, rosterFace.detectionScore());
                bumpLastSeen(sessionId, match.studentId(), now);
                continue;
            }

            if (match.confidence() >= autoMarkThreshold) {
                processAutoMark(session, rosterStudent, match, rosterFace, frameAnalysis, now, hasPending);
            } else if (match.confidence() >= REVIEW_LOWER_BOUND) {
                queueForReview(session, rosterStudent, match, rosterFace, frameAnalysis, component.size());
            } else {
                publishSkip(sessionId, "LOW_CONFIDENCE", rosterFace.box(), frameAnalysis, match, rosterFace.detectionScore());
            }
        }

        List<Map<String, Object>> overlayBoxes = new ArrayList<>();
        for (DetectedFace face : frameAnalysis.faces()) {
            BoundingBox box = face.box();
            if (box == null) {
                continue;
            }
            String label;
            if (!face.candidates().isEmpty()) {
                MatchCandidate best = face.candidates().get(0);
                Student candidateStudent = roster.get(best.studentId());
                if (candidateStudent != null) {
                    StringBuilder builder = new StringBuilder(candidateStudent.getFullName());
                    if (best.masked()) {
                        builder.append(" [mask]");
                    }
                    builder.append(" (").append(formatConfidence(best.confidence())).append(")");
                    label = builder.toString();
                } else {
                    label = "Not in roster";
                }
            } else {
                label = "Not in roster";
            }
            overlayBoxes.add(boundingBoxPayload(box, frameAnalysis, label));
        }

        if (!overlayBoxes.isEmpty()) {
            recognitionEventService.publish(sessionId, "recognition.overlay", Map.of(
                    "boxes", overlayBoxes,
                    "timestamp", LocalDateTime.now().toString()
            ));
        }
    }

    public void confirmReview(String token, boolean accepted, Integer markedByUserId) {
        PendingRecognitionStore.PendingRecognition pending = pendingRecognitionStore.consume(token)
                .orElseThrow(() -> new IllegalArgumentException("Review token not found or expired"));

        Integer sessionId = pending.sessionId();
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));

        if (session.getStatus() != SessionStatus.OPEN) {
            throw new IllegalStateException("Session is not open");
        }

        MatchCandidate candidate = pending.candidate();
        if (!accepted) {
            publishSkip(sessionId, "REJECTED", pending.boundingBox(),
                    new FaceRecognitionService.FrameAnalysisResult(pending.frameWidth(), pending.frameHeight(), List.of()),
                    candidate,
                    pending.detectionScore());
            recognitionEventService.publish(sessionId, "recognition.review-rejected", Map.of(
                    "token", token,
                    "studentId", candidate.studentId(),
                    "timestamp", LocalDateTime.now().toString()
            ));
            return;
        }

        Map<String, Student> roster = loadRoster(sessionId);
        Student rosterStudent = roster.get(candidate.studentId());
        if (rosterStudent == null) {
            publishSkip(sessionId, "NOT_IN_ROSTER", pending.boundingBox(),
                    new FaceRecognitionService.FrameAnalysisResult(pending.frameWidth(), pending.frameHeight(), List.of()),
                    candidate,
                    pending.detectionScore());
            return;
        }

        LocalDateTime now = LocalDateTime.now();
        AttendanceRecord record = attendanceRecordRepository
                .findBySessionSessionIdAndStudentStudentId(sessionId, candidate.studentId())
                .orElse(null);

        User markedBy = actionUser(markedByUserId);

        boolean fresh = false;
        if (record == null) {
            record = createAttendanceRecord(session, rosterStudent, now, candidate.confidence(), pending.detectionScore(), MarkingMethod.MANUAL, markedBy);
            fresh = true;
        } else {
            if (record.getMarkingMethod() == MarkingMethod.MANUAL && record.getStatus() == AttendanceStatus.PRESENT) {
                publishSkip(sessionId, "ALREADY_MANUAL", pending.boundingBox(),
                        new FaceRecognitionService.FrameAnalysisResult(pending.frameWidth(), pending.frameHeight(), List.of()),
                        candidate,
                        pending.detectionScore());
                return;
            }
            updateAttendanceRecord(session, record, now, candidate.confidence(), pending.detectionScore(), MarkingMethod.MANUAL, markedBy);
        }

        attendanceRecordRepository.save(record);
        if (fresh) {
            recognitionEventService.publish(sessionId, "recognition.review-cleared", Map.of(
                    "token", token,
                    "studentId", candidate.studentId(),
                    "studentName", rosterStudent.getFullName(),
                    "timestamp", now.toString()
            ));
        }

        attendanceAuditLogger.logManualOverride(sessionId, candidate.studentId(), "REVIEW", record.getStatus().name(), markedByUserId);
        recognitionEventService.publish(sessionId, "recognition.review-confirmed", Map.of(
                "token", token,
                "studentId", candidate.studentId(),
                "studentName", rosterStudent.getFullName(),
                "confidence", candidate.confidence(),
                "status", record.getStatus().name(),
                "timestamp", LocalDateTime.now().toString()
        ));
        updateLastSeenCache(sessionId, candidate.studentId(), now);
    }

    private void processAutoMark(Session session,
                                 Student rosterStudent,
                                 MatchCandidate match,
                                 DetectedFace face,
                                 FaceRecognitionService.FrameAnalysisResult frameAnalysis,
                                 LocalDateTime now,
                                 boolean bypassCooldown) {
        Integer sessionId = session.getSessionId();
        String studentId = match.studentId();

        if (!bypassCooldown && isWithinCooldown(sessionId, studentId, now)) {
            publishSkip(sessionId, "COOLDOWN", face.box(), frameAnalysis, match, face.detectionScore());
            bumpLastSeen(sessionId, studentId, now);
            return;
        }

        AttendanceRecord record = attendanceRecordRepository
                .findBySessionSessionIdAndStudentStudentId(sessionId, studentId)
                .orElse(null);

        if (record != null && record.getMarkingMethod() == MarkingMethod.MANUAL && record.getStatus() != AttendanceStatus.ABSENT) {
            publishSkip(sessionId, "MANUAL_LOCK", face.box(), frameAnalysis, match, face.detectionScore());
            return;
        }

        String displayName = rosterStudent != null ? rosterStudent.getFullName() : match.displayName();

        boolean fresh = false;
        if (record == null) {
            record = createAttendanceRecord(session, rosterStudent, now, match.confidence(), face.detectionScore(), MarkingMethod.AUTO, null);
            fresh = true;
        } else {
            updateAttendanceRecord(session, record, now, match.confidence(), face.detectionScore(), MarkingMethod.AUTO, null);
        }

        attendanceRecordRepository.save(record);
        updateLastSeenCache(sessionId, studentId, now);

        pendingRecognitionStore.findBySessionAndStudent(sessionId, studentId)
                .ifPresent(existing -> {
                    pendingRecognitionStore.discard(existing.token());
                    recognitionEventService.publish(sessionId, "recognition.review-cleared", Map.of(
                            "token", existing.token(),
                            "studentId", studentId,
                            "studentName", rosterStudent.getFullName(),
                            "timestamp", LocalDateTime.now().toString()
                    ));
                });

        String label = displayName +
                (match.masked() ? " [mask]" : "") +
                " (" + formatConfidence(match.confidence()) + ")";
        attendanceAuditLogger.logRecognitionAutoMark(
                sessionId,
                studentId,
                BigDecimal.valueOf(match.confidence()),
                record.getStatus().name(),
                record.getMarkingMethod().name()
        );

        recognitionEventService.publish(sessionId, "recognition.auto-marked", Map.of(
                "studentId", studentId,
                "studentName", displayName,
                "confidence", match.confidence(),
                "status", record.getStatus().name(),
                "fresh", fresh,
                "label", label,
                "maskStatus", match.masked() ? "MASKED" : "UNMASKED",
                "boundingBox", boundingBoxPayload(face.box(), frameAnalysis, label),
                "timestamp", LocalDateTime.now().toString()
        ));
    }

    private void queueForReview(Session session,
                                Student rosterStudent,
                                MatchCandidate match,
                                DetectedFace face,
                                FaceRecognitionService.FrameAnalysisResult frameAnalysis,
                                int faceCount) {
        Optional<PendingRecognitionStore.PendingRecognition> existingPending =
                pendingRecognitionStore.findBySessionAndStudent(session.getSessionId(), match.studentId());

        List<MatchCandidate> alternatives = face.candidates().stream()
                .filter(candidate -> !candidate.studentId().equals(match.studentId()))
                .sorted(Comparator.comparingDouble(MatchCandidate::confidence).reversed())
                .collect(Collectors.toList());

        if (existingPending.isPresent()) {
            PendingRecognitionStore.PendingRecognition updated = pendingRecognitionStore.refresh(
                    existingPending.get().token(),
                    session.getSessionId(),
                    match,
                    alternatives,
                    face.box(),
                    frameAnalysis.frameWidth(),
                    frameAnalysis.frameHeight(),
                    face.detectionScore()
            );

            recognitionEventService.publish(session.getSessionId(), "recognition.review-update", Map.of(
                    "token", updated.token(),
                    "studentId", match.studentId(),
                    "studentName", rosterStudent.getFullName(),
                    "confidence", match.confidence(),
                    "label", rosterStudent.getFullName()
                            + (match.masked() ? " [mask]" : "")
                            + " (" + formatConfidence(match.confidence()) + ")",
                    "faceCount", faceCount,
                    "maskStatus", match.masked() ? "MASKED" : "UNMASKED",
                    "alternatives", alternatives.stream().map(candidate -> Map.of(
                            "studentId", candidate.studentId(),
                            "studentName", candidate.displayName(),
                            "confidence", candidate.confidence(),
                            "maskStatus", candidate.masked() ? "MASKED" : "UNMASKED"
                    )).toList(),
                    "boundingBox", boundingBoxPayload(face.box(), frameAnalysis,
                            rosterStudent.getFullName()
                                    + (match.masked() ? " [mask]" : "")
                                    + " (" + formatConfidence(match.confidence()) + ")"),
                    "timestamp", LocalDateTime.now().toString()
            ));
            updateLastSeenCache(session.getSessionId(), match.studentId(), LocalDateTime.now());
            return;
        }

        String label = rosterStudent.getFullName()
                + (match.masked() ? " [mask]" : "")
                + " (" + formatConfidence(match.confidence()) + ")";
        PendingRecognitionStore.PendingRecognition pending = pendingRecognitionStore.create(
                session.getSessionId(),
                match,
                alternatives,
                face.box(),
                frameAnalysis.frameWidth(),
                frameAnalysis.frameHeight(),
                face.detectionScore()
        );

        recognitionEventService.publish(session.getSessionId(), "recognition.review", Map.of(
                "token", pending.token(),
                "studentId", match.studentId(),
                "studentName", rosterStudent.getFullName(),
                "confidence", match.confidence(),
                "label", label,
                "faceCount", faceCount,
                "maskStatus", match.masked() ? "MASKED" : "UNMASKED",
                "alternatives", alternatives.stream().map(candidate -> Map.of(
                        "studentId", candidate.studentId(),
                        "studentName", candidate.displayName(),
                        "confidence", candidate.confidence(),
                        "maskStatus", candidate.masked() ? "MASKED" : "UNMASKED"
                )).toList(),
                "boundingBox", boundingBoxPayload(face.box(), frameAnalysis, label),
                "timestamp", LocalDateTime.now().toString()
        ));

        updateLastSeenCache(session.getSessionId(), match.studentId(), LocalDateTime.now());
    }

    private void publishSkip(Integer sessionId,
                             String reason,
                             BoundingBox box,
                             FaceRecognitionService.FrameAnalysisResult frameAnalysis,
                             MatchCandidate candidate,
                             double detectionScore) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("reason", reason);
        payload.put("timestamp", LocalDateTime.now().toString());
        String label = candidate != null
                ? candidate.displayName() + " (" + formatConfidence(candidate.confidence()) + ")"
                : reason;
        payload.put("label", label);
        payload.put("boundingBox", boundingBoxPayload(box, frameAnalysis, label));
        payload.put("detectionScore", detectionScore);
        if (candidate != null) {
            payload.put("studentId", candidate.studentId());
            payload.put("studentName", candidate.displayName());
            payload.put("confidence", candidate.confidence());
        }
        recognitionEventService.publish(sessionId, "recognition.skipped", payload);
        attendanceAuditLogger.logRecognitionSkipped(sessionId, reason);
    }

    private Map<String, Object> boundingBoxPayload(BoundingBox box,
                                                   FaceRecognitionService.FrameAnalysisResult frameAnalysis,
                                                   String label) {
        if (box == null) {
            return Map.of();
        }
        Map<String, Object> map = new HashMap<>();
        map.put("x", box.x());
        map.put("y", box.y());
        map.put("width", box.width());
        map.put("height", box.height());
        map.put("frameWidth", frameAnalysis.frameWidth());
        map.put("frameHeight", frameAnalysis.frameHeight());
        if (label != null && !label.isBlank()) {
            map.put("label", label);
        }
        return map;
    }

    private String formatConfidence(double value) {
        return String.format(Locale.US, "%.1f%%", value);
    }

    private AttendanceRecord createAttendanceRecord(Session session,
                                                    Student student,
                                                    LocalDateTime now,
                                                    double confidence,
                                                    double detectionScore,
                                                    MarkingMethod method,
                                                    User markedBy) {
        AttendanceStatus status = determineStatus(session, now);
        AttendanceRecord record = new AttendanceRecord(session, student, status, method);
        record.setMarkedAt(now);
        record.setLastSeen(now);
        record.setConfidenceScore(BigDecimal.valueOf(confidence));
        record.setNotes(null);
        record.setMarkedBy(markedBy);
        record.setUpdatedAt(now);
        return record;
    }

    private void updateAttendanceRecord(Session session,
                                        AttendanceRecord record,
                                        LocalDateTime now,
                                        double confidence,
                                        double detectionScore,
                                        MarkingMethod method,
                                        User markedBy) {
        AttendanceStatus status = determineStatus(session, now);
        record.setStatus(status);
        record.setMarkedAt(now);
        record.setLastSeen(now);
        record.setConfidenceScore(BigDecimal.valueOf(confidence));
        record.setMarkingMethod(method);
        record.setUpdatedAt(now);
        if (markedBy != null) {
            record.setMarkedBy(markedBy);
        }
        if (method != MarkingMethod.AUTO && (record.getNotes() == null || record.getNotes().isBlank())) {
            record.setNotes("Confirmed from review queue");
        } else if (method == MarkingMethod.AUTO) {
            record.setNotes(null);
        }
    }

    private AttendanceStatus determineStatus(Session session, LocalDateTime now) {
        LocalDate sessionDate = session.getSessionDate();
        LocalTime startTime = session.getStartTime();
        int lateThresholdMinutes = session.getLateThresholdMinutes();
        LocalDateTime startDateTime = sessionDate.atTime(startTime);
        LocalDateTime lateThreshold = startDateTime.plusMinutes(lateThresholdMinutes);
        return now.isAfter(lateThreshold) ? AttendanceStatus.LATE : AttendanceStatus.PRESENT;
    }

    private boolean isWithinCooldown(Integer sessionId, String studentId, LocalDateTime now) {
        Map<String, LocalDateTime> sessionMap = lastSeenCache.computeIfAbsent(sessionId, ignored -> new ConcurrentHashMap<>());
        LocalDateTime lastSeen = sessionMap.get(studentId);
        if (lastSeen == null) {
            AttendanceRecord record = attendanceRecordRepository
                    .findBySessionSessionIdAndStudentStudentId(sessionId, studentId)
                    .orElse(null);
            lastSeen = record != null ? record.getLastSeen() : null;
        }
        if (lastSeen == null) {
            return false;
        }
        Duration elapsed = Duration.between(lastSeen, now);
        return elapsed.getSeconds() < COOLDOWN_SECONDS;
    }

    private void bumpLastSeen(Integer sessionId, String studentId, LocalDateTime now) {
        Map<String, LocalDateTime> sessionMap = lastSeenCache.computeIfAbsent(sessionId, ignored -> new ConcurrentHashMap<>());
        sessionMap.put(studentId, now);
        attendanceRecordRepository.findBySessionSessionIdAndStudentStudentId(sessionId, studentId)
                .ifPresent(record -> {
                    record.setLastSeen(now);
                    attendanceRecordRepository.save(record);
                });
    }

    private void updateLastSeenCache(Integer sessionId, String studentId, LocalDateTime now) {
        lastSeenCache
                .computeIfAbsent(sessionId, ignored -> new ConcurrentHashMap<>())
                .put(studentId, now);
    }

    private User actionUser(Integer userId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findById(userId).orElse(null);
    }

    private Map<String, Student> loadRoster(Integer sessionId) {
        return rosterCache.computeIfAbsent(sessionId, ignored -> {
            List<SessionRoster> rosterEntries = sessionRosterRepository.findRosterWithStudent(sessionId);
            Map<String, Student> roster = new HashMap<>();
            for (SessionRoster rosterEntry : rosterEntries) {
                Student student = rosterEntry.getStudent();
                if (student != null) {
                    roster.put(student.getStudentId(), student);
                }
            }
            return roster;
        });
    }
}
