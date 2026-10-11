package com.smartattendance.service;

import com.smartattendance.entity.AttendanceRecord;
import com.smartattendance.entity.AttendanceRecord.AttendanceStatus;
import com.smartattendance.entity.AttendanceRecord.MarkingMethod;
import com.smartattendance.entity.Section;
import com.smartattendance.entity.SectionEnrolments;
import com.smartattendance.entity.Session;
import com.smartattendance.entity.Session.SessionStatus;
import com.smartattendance.entity.SessionRoster;
import com.smartattendance.entity.Student;
import com.smartattendance.entity.User;
import com.smartattendance.repository.AttendanceRecordRepository;
import com.smartattendance.repository.SectionEnrolmentsRepository;
import com.smartattendance.repository.SectionRepository;
import com.smartattendance.repository.SessionRepository;
import com.smartattendance.repository.SessionRosterRepository;
import com.smartattendance.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class SessionService {

    private final SessionRepository sessionRepository;
    private final SectionRepository sectionRepository;
    private final SessionRosterRepository sessionRosterRepository;
    private final AttendanceRecordRepository attendanceRecordRepository;
    private final SectionEnrolmentsRepository sectionEnrolmentsRepository;
    private final UserRepository userRepository;

    @Autowired
    public SessionService(SessionRepository sessionRepository,
                          SectionRepository sectionRepository,
                          SessionRosterRepository sessionRosterRepository,
                          AttendanceRecordRepository attendanceRecordRepository,
                          SectionEnrolmentsRepository sectionEnrolmentsRepository,
                          UserRepository userRepository) {
        this.sessionRepository = sessionRepository;
        this.sectionRepository = sectionRepository;
        this.sessionRosterRepository = sessionRosterRepository;
        this.attendanceRecordRepository = attendanceRecordRepository;
        this.sectionEnrolmentsRepository = sectionEnrolmentsRepository;
        this.userRepository = userRepository;
    }

    public SessionCreationResult createSession(Integer sectionId,
                                               LocalDate sessionDate,
                                               LocalTime startTime,
                                               LocalTime endTime,
                                               String location,
                                               Integer lateThresholdMinutes,
                                               Integer createdByUserId) {
        if (sectionId == null) {
            throw new IllegalArgumentException("Section ID is required");
        }
        if (sessionDate == null) {
            throw new IllegalArgumentException("Session date is required");
        }
        if (startTime == null || endTime == null) {
            throw new IllegalArgumentException("Start and end time are required");
        }
        if (!endTime.isAfter(startTime)) {
            throw new IllegalArgumentException("End time must be after start time");
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime startDateTime = LocalDateTime.of(sessionDate, startTime);
        LocalDateTime endDateTime = LocalDateTime.of(sessionDate, endTime);

        if (startDateTime.isBefore(now)) {
            throw new IllegalArgumentException("Session start time must be in the future");
        }
        if (!endDateTime.isAfter(now)) {
            throw new IllegalArgumentException("Session end time must be in the future");
        }

        Section section = sectionRepository.findById(sectionId)
                .orElseThrow(() -> new IllegalArgumentException("Section not found"));

        if (section.getProfessor() != null
                && section.getProfessor().getUserId() != null) {
            List<Session> overlaps = sessionRepository.findProfessorOverlaps(
                    section.getProfessor().getUserId(),
                    sessionDate,
                    startTime,
                    endTime,
                    null
            );
            if (!overlaps.isEmpty()) {
                Session conflict = overlaps.get(0);
                String professorName = Optional.ofNullable(section.getProfessor().getFullName())
                        .orElse("This professor");
                String conflictCourse = Optional.ofNullable(conflict.getSection())
                        .map(Section::getCourse)
                        .map(course -> course.getCourseCode() != null ? course.getCourseCode() : course.getCourseName())
                        .orElse("another course");
                String conflictSection = Optional.ofNullable(conflict.getSection())
                        .map(Section::getSectionCode)
                        .orElse("");
                String sectionDisplay = conflictSection.isBlank() ? "" : " (" + conflictSection + ")";
                throw new IllegalArgumentException(String.format(
                        "%s already has %s%s scheduled on %s from %s to %s. Please pick a different time slot or delete the existing session.",
                        professorName,
                        conflictCourse,
                        sectionDisplay,
                        conflict.getSessionDate(),
                        conflict.getStartTime(),
                        conflict.getEndTime()
                ));
            }
        }

        boolean duplicateExists = sessionRepository.existsBySectionSectionIdAndSessionDateAndStartTimeAndEndTime(
                sectionId, sessionDate, startTime, endTime);
        if (duplicateExists) {
            throw new IllegalArgumentException("A session for this section with the same start and end time already exists for the selected date.");
        }

        boolean hasOverlap = sessionRepository.existsOverlappingSession(
                sectionId, sessionDate, startTime, endTime, null);

        Session session = new Session();
        session.setSection(section);
        session.setSessionDate(sessionDate);
        session.setStartTime(startTime);
        session.setEndTime(endTime);
        session.setLocation(location != null && !location.isBlank() ? location.trim() : null);
        session.setLateThresholdMinutes(lateThresholdMinutes != null && lateThresholdMinutes >= 0
                ? lateThresholdMinutes
                : 15);
        session.setStatus(SessionStatus.PENDING);

        if (createdByUserId != null) {
            userRepository.findById(createdByUserId).ifPresent(session::setCreatedBy);
        }

        Session savedSession = sessionRepository.save(session);

        populateRosterFromEnrolments(savedSession, createdByUserId);

        return new SessionCreationResult(savedSession, hasOverlap);
    }

    @Transactional(readOnly = true)
    public List<Session> getAllSessionsWithSection() {
        return sessionRepository.findAllWithSectionAndCourse();
    }

    @Transactional(readOnly = true)
    public Optional<Session> getSessionWithSection(Integer sessionId) {
        return sessionRepository.findByIdWithSectionAndCourse(sessionId);
    }

    public Session startSession(Integer sessionId, Integer userId) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));

        if (session.getStatus() == SessionStatus.CLOSED) {
            throw new IllegalStateException("Session already closed");
        }
        if (session.getStatus() == SessionStatus.OPEN) {
            return session;
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime scheduledStart = session.getSessionDate().atTime(session.getStartTime());
        LocalDateTime scheduledEnd = session.getSessionDate().atTime(session.getEndTime());
        LocalDateTime startWindow = scheduledStart.minusMinutes(15);

        if (now.isBefore(startWindow)) {
            throw new IllegalStateException("Sessions can only be started within 15 minutes before the scheduled start time");
        }
        if (now.isAfter(scheduledEnd)) {
            throw new IllegalStateException("This session has already ended and cannot be started");
        }

        populateRosterFromEnrolments(session, userId);

        session.setStatus(SessionStatus.OPEN);
        session.setOpenedAt(LocalDateTime.now());
        return sessionRepository.save(session);
    }

    public Session closeSession(Integer sessionId, Integer userId) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));

        if (session.getStatus() != SessionStatus.OPEN) {
            throw new IllegalStateException("Only open sessions can be closed");
        }

        return closeSessionInternal(session, userId, false);
    }

    public int autoCloseExpiredSessions() {
        LocalDate today = LocalDate.now();
        LocalTime nowTime = LocalTime.now();
        List<Session> expiredSessions = sessionRepository.findExpiredSessions(today, nowTime, SessionStatus.CLOSED);

        int closedCount = 0;
        for (Session session : expiredSessions) {
            SessionStatus status = session.getStatus();
            if (status == SessionStatus.CLOSED) {
                continue;
            }
            closeSessionInternal(session, null, true);
            closedCount++;
        }
        return closedCount;
    }

    public void deleteSession(Integer sessionId) {
        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));

        if (session.getStatus() != SessionStatus.CLOSED) {
            throw new IllegalStateException("Only closed sessions can be deleted.");
        }

        attendanceRecordRepository.deleteBySessionSessionId(sessionId);
        sessionRosterRepository.deleteBySessionSessionId(sessionId);
        sessionRepository.delete(session);
    }

    public List<AttendanceRecord> applyAttendanceUpdates(Integer sessionId,
                                                          List<AttendanceUpdate> updates,
                                                          Integer markedByUserId) {
        if (updates == null || updates.isEmpty()) {
            return List.of();
        }

        Session session = sessionRepository.findById(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));

        if (session.getStatus() == SessionStatus.PENDING) {
            throw new IllegalStateException("Cannot modify attendance for pending session");
        }

        User markedBy = markedByUserId != null ? userRepository.findById(markedByUserId).orElse(null) : null;
        Map<String, AttendanceRecord> existingRecords = attendanceRecordRepository.findBySessionSessionId(sessionId)
                .stream()
                .collect(Collectors.toMap(
                        record -> record.getStudent().getStudentId(),
                        record -> record
                ));

        Map<String, Student> rosterStudents = sessionRosterRepository.findRosterWithStudent(sessionId)
                .stream()
                .collect(Collectors.toMap(
                        roster -> roster.getStudent().getStudentId(),
                        SessionRoster::getStudent
                ));

        List<AttendanceRecord> savedRecords = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (AttendanceUpdate update : updates) {
            if (update == null || update.studentId() == null || update.studentId().isBlank()) {
                continue;
            }
            String studentId = update.studentId().trim();
            Student student = rosterStudents.get(studentId);
            if (student == null) {
                // Skip updates for students not in roster
                continue;
            }

            AttendanceStatus newStatus = update.status();
            if (newStatus == null) {
                continue;
            }

            AttendanceRecord record = existingRecords.get(studentId);
            if (record == null) {
                record = new AttendanceRecord(session, student, newStatus, MarkingMethod.MANUAL);
                existingRecords.put(studentId, record);
            } else {
                record.setStatus(newStatus);
                record.setMarkingMethod(MarkingMethod.MANUAL);
            }

            record.setMarkedAt(now);
            record.setMarkedBy(markedBy);
            record.setNotes(update.notes() != null && !update.notes().isBlank() ? update.notes().trim() : null);

            attendanceRecordRepository.save(record);
            savedRecords.add(record);
        }

        return savedRecords;
    }

    @Transactional(readOnly = true)
    public AttendanceSummary getAttendanceSummary(Integer sessionId) {
        long totalRoster = sessionRosterRepository.countBySessionSessionId(sessionId);
        Map<AttendanceStatus, Long> counts = new EnumMap<>(AttendanceStatus.class);
        for (Object[] row : attendanceRecordRepository.countByStatus(sessionId)) {
            AttendanceStatus status = (AttendanceStatus) row[0];
            Long count = (Long) row[1];
            counts.put(status, count);
        }

        long present = counts.getOrDefault(AttendanceStatus.PRESENT, 0L);
        long absent = counts.getOrDefault(AttendanceStatus.ABSENT, 0L);
        long late = counts.getOrDefault(AttendanceStatus.LATE, 0L);
        long recorded = present + absent + late;

        return new AttendanceSummary(totalRoster, present, absent, late, recorded);
    }

    @Transactional(readOnly = true)
    public Map<Integer, AttendanceSummary> getAttendanceSummaryForSessions(List<Integer> sessionIds) {
        if (sessionIds == null || sessionIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Integer, Long> rosterCounts = sessionRosterRepository.countBySession(sessionIds)
                .stream()
                .collect(Collectors.toMap(
                        row -> (Integer) row[0],
                        row -> (Long) row[1]
                ));

        Map<Integer, EnumMap<AttendanceStatus, Long>> statusCounts = new HashMap<>();
        for (Object[] row : attendanceRecordRepository.countBySessionAndStatus(sessionIds)) {
            Integer sessionId = (Integer) row[0];
            AttendanceStatus status = (AttendanceStatus) row[1];
            Long count = (Long) row[2];

            statusCounts
                    .computeIfAbsent(sessionId, ignored -> new EnumMap<>(AttendanceStatus.class))
                    .put(status, count);
        }

        Map<Integer, AttendanceSummary> summaryMap = new HashMap<>();
        for (Integer sessionId : sessionIds) {
            long rosterCount = rosterCounts.getOrDefault(sessionId, 0L);
            EnumMap<AttendanceStatus, Long> counts = statusCounts.getOrDefault(sessionId, new EnumMap<>(AttendanceStatus.class));
            long present = counts.getOrDefault(AttendanceStatus.PRESENT, 0L);
            long absent = counts.getOrDefault(AttendanceStatus.ABSENT, 0L);
            long late = counts.getOrDefault(AttendanceStatus.LATE, 0L);
            long recorded = present + absent + late;
            summaryMap.put(sessionId, new AttendanceSummary(rosterCount, present, absent, late, recorded));
        }

        return summaryMap;
    }

    @Transactional(readOnly = true)
    public List<RosterEntryDetail> getRosterWithAttendance(Integer sessionId) {
        List<SessionRoster> roster = sessionRosterRepository.findRosterWithStudent(sessionId);
        Map<String, AttendanceRecord> records = attendanceRecordRepository.findBySessionSessionId(sessionId)
                .stream()
                .collect(Collectors.toMap(
                        record -> record.getStudent().getStudentId(),
                        record -> record
                ));

        List<RosterEntryDetail> result = new ArrayList<>();
        for (SessionRoster entry : roster) {
            AttendanceRecord record = records.get(entry.getStudent().getStudentId());
            result.add(new RosterEntryDetail(entry, record));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public boolean hasOverlap(Integer sectionId,
                              LocalDate sessionDate,
                              LocalTime startTime,
                              LocalTime endTime,
                              Integer excludeSessionId) {
        return sessionRepository.existsOverlappingSession(sectionId, sessionDate, startTime, endTime, excludeSessionId);
    }

    public List<String> populateRosterFromEnrolments(Session session, Integer addedByUserId) {
        Section section = session.getSection();
        if (section == null) {
            throw new IllegalStateException("Session has no section associated");
        }

        User addedBy = addedByUserId != null ? userRepository.findById(addedByUserId).orElse(null) : null;
        List<SectionEnrolments> enrolments = sectionEnrolmentsRepository.findBySectionSectionIdAndStatus(
                section.getSectionId(),
                SectionEnrolments.EnrolmentStatus.ENROLLED
        );

        List<String> insertedStudents = new ArrayList<>();
        for (SectionEnrolments enrolment : enrolments) {
            Student student = enrolment.getStudent();
            if (student == null) {
                continue;
            }
            String studentId = student.getStudentId();
            if (sessionRosterRepository.existsBySessionSessionIdAndStudentStudentId(session.getSessionId(), studentId)) {
                continue;
            }
            SessionRoster rosterEntry = new SessionRoster(session, student, addedBy);
            sessionRosterRepository.save(rosterEntry);
            insertedStudents.add(studentId);
        }

        return insertedStudents;
    }

    public void refreshSectionSessionRosters(Integer sectionId, Integer addedByUserId) {
        if (sectionId == null) {
            return;
        }
        List<Session> sessions = sessionRepository.findBySectionSectionId(sectionId);
        for (Session session : sessions) {
            populateRosterFromEnrolments(session, addedByUserId);
        }
    }

    public record SessionCreationResult(Session session, boolean overlapDetected) {}

    public record AttendanceUpdate(String studentId, AttendanceStatus status, String notes) {}

    public record AttendanceSummary(long rosterCount, long presentCount, long absentCount, long lateCount, long recordedCount) {}

    public record RosterEntryDetail(SessionRoster rosterEntry, AttendanceRecord attendanceRecord) {}

    @Transactional(readOnly = true)
    public List<Session> getSessionsVisibleTo(User user) {
        if (user == null) {
            return List.of();
        }
        if (user.getRole() == User.Role.ADMIN) {
            return getAllSessionsWithSection();
        }
        return getAllSessionsWithSection().stream()
                .filter(session -> canUserAccessSession(user, session))
                .toList();
    }

    @Transactional(readOnly = true)
    public boolean canUserAccessSession(User user, Session session) {
        if (user == null || session == null) {
            return false;
        }
        if (user.getRole() == User.Role.ADMIN) {
            return true;
        }
        Section section = resolveSection(session);
        if (section == null) {
            return false;
        }
        if (user.getRole() == User.Role.PROFESSOR) {
            return section.getProfessor() != null
                    && Objects.equals(section.getProfessor().getUserId(), user.getUserId());
        }
        if (user.getRole() == User.Role.TEACHING_ASSISTANT) {
            boolean ta1 = section.getTeachingAssistant1() != null
                    && Objects.equals(section.getTeachingAssistant1().getUserId(), user.getUserId());
            boolean ta2 = section.getTeachingAssistant2() != null
                    && Objects.equals(section.getTeachingAssistant2().getUserId(), user.getUserId());
            return ta1 || ta2;
        }
        return false;
    }

    private Section resolveSection(Session session) {
        Section section = session.getSection();
        if (section != null) {
            // Ensure proxies are initialized
            section.getSectionCode();
            return section;
        }
        return sessionRepository.findByIdWithSectionAndCourse(session.getSessionId())
                .map(Session::getSection)
                .orElse(null);
    }

    private Session closeSessionInternal(Session session, Integer userId, boolean allowPending) {
        SessionStatus previousStatus = session.getStatus();
        if (previousStatus == SessionStatus.CLOSED) {
            return session;
        }
        if (!allowPending && previousStatus != SessionStatus.OPEN) {
            throw new IllegalStateException("Only open sessions can be closed");
        }

        if (allowPending || previousStatus == SessionStatus.OPEN) {
            populateRosterFromEnrolments(session, userId);
        }

        User user = userId != null ? userRepository.findById(userId).orElse(null) : null;

        List<SessionRoster> rosterEntries = sessionRosterRepository.findBySessionSessionId(session.getSessionId());
        Set<String> recordedStudents = new HashSet<>(attendanceRecordRepository.findStudentIdsBySession(session.getSessionId()));

        List<AttendanceRecord> autoAbsentees = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (SessionRoster roster : rosterEntries) {
            String studentId = roster.getStudent().getStudentId();
            if (!recordedStudents.contains(studentId)) {
                AttendanceRecord record = new AttendanceRecord(
                        session,
                        roster.getStudent(),
                        AttendanceStatus.ABSENT,
                        MarkingMethod.AUTO
                );
                record.setMarkedAt(now);
                record.setMarkedBy(user);
                autoAbsentees.add(record);
            }
        }

        if (!autoAbsentees.isEmpty()) {
            attendanceRecordRepository.saveAll(autoAbsentees);
        }

        session.setStatus(SessionStatus.CLOSED);
        session.setClosedAt(now);
        return sessionRepository.save(session);
    }
}
