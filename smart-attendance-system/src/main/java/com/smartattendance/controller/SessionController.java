package com.smartattendance.controller;

import com.smartattendance.entity.AttendanceRecord;
import com.smartattendance.entity.Session;
import com.smartattendance.entity.User;
import com.smartattendance.security.SecurityUtils;
import com.smartattendance.service.SectionService;
import com.smartattendance.service.SessionExportService;
import com.smartattendance.service.SessionService;
import com.smartattendance.service.UserService;
import com.smartattendance.service.auth.UserPrincipal;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/sessions")
@CrossOrigin(origins = "*")
public class SessionController {

    private final SessionService sessionService;
    private final SessionExportService sessionExportService;
    private final SectionService sectionService;
    private final UserService userService;

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Autowired
    public SessionController(SessionService sessionService,
                             SessionExportService sessionExportService,
                             SectionService sectionService,
                             UserService userService) {
        this.sessionService = sessionService;
        this.sessionExportService = sessionExportService;
        this.sectionService = sectionService;
        this.userService = userService;
    }

    @PreAuthorize("hasAnyRole('ADMIN','PROFESSOR','TEACHING_ASSISTANT')")
    @GetMapping
    public ResponseEntity<ApiResponse> listSessions(@RequestParam(required = false) String bucket,
                                                    @RequestParam(required = false) String search,
                                                    @RequestParam(required = false) String status) {
        try {
            sessionService.autoCloseExpiredSessions();
            User currentUser = getCurrentUser();
            List<Session> sessions = sessionService.getSessionsVisibleTo(currentUser);
            List<Integer> sessionIds = sessions.stream()
                    .map(Session::getSessionId)
                    .toList();
            Map<Integer, SessionService.AttendanceSummary> summaries = sessionService.getAttendanceSummaryForSessions(sessionIds);

            LocalDateTime now = LocalDateTime.now();

            String normalizedBucket = bucket != null ? bucket.trim().toLowerCase(Locale.ROOT) : null;
            String normalizedSearch = search != null ? search.trim().toLowerCase(Locale.ROOT) : null;
            String normalizedStatus = status != null ? status.trim().toUpperCase(Locale.ROOT) : null;

            List<Map<String, Object>> data = new ArrayList<>();
            for (Session session : sessions) {
                SessionService.AttendanceSummary summary = summaries.getOrDefault(
                        session.getSessionId(),
                        new SessionService.AttendanceSummary(0, 0, 0, 0, 0)
                );
                Map<String, Object> map = convertSessionToMap(session, summary, now);

                if (normalizedBucket != null && !normalizedBucket.isEmpty()) {
                    String sessionBucket = ((String) map.get("bucket")).toLowerCase(Locale.ROOT);
                    if (!normalizedBucket.equals(sessionBucket)) {
                        continue;
                    }
                }

                if (normalizedStatus != null && !normalizedStatus.isEmpty()) {
                    String sessionStatus = (String) map.get("status");
                    if (!normalizedStatus.equalsIgnoreCase(sessionStatus)) {
                        continue;
                    }
                }

                if (normalizedSearch != null && !normalizedSearch.isEmpty()) {
                    String combined = String.join(" ",
                            Objects.toString(map.get("courseCode"), ""),
                            Objects.toString(map.get("courseName"), ""),
                            Objects.toString(map.get("sectionCode"), ""),
                            Objects.toString(map.get("location"), "")
                    ).toLowerCase(Locale.ROOT);
                    if (!combined.contains(normalizedSearch)) {
                        continue;
                    }
                }

                data.add(map);
            }

            return ResponseEntity.ok(new ApiResponse(true, "Sessions retrieved successfully", data));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error retrieving sessions: " + e.getMessage()));
        }
    }

    @PreAuthorize("hasAnyRole('ADMIN','PROFESSOR')")
    @PostMapping
    public ResponseEntity<ApiResponse> createSession(@Valid @RequestBody SessionCreateRequest request) {
        try {
            User currentUser = getCurrentUser();
            if (currentUser.getRole() == User.Role.PROFESSOR) {
                SectionService sectionSvc = this.sectionService;
                sectionSvc.getSectionById(request.getSectionId())
                        .filter(section -> sectionSvc.canUserAccessSection(currentUser, section))
                        .orElseThrow(() -> new IllegalArgumentException("You are not permitted to create a session for this section"));
            }
            SessionService.SessionCreationResult result = sessionService.createSession(
                    request.getSectionId(),
                    parseDate(request.getSessionDate()),
                    parseTime(request.getStartTime()),
                    parseTime(request.getEndTime()),
                    request.getLocation(),
                    request.getLateThresholdMinutes(),
                    currentUser.getUserId()
            );

            Session savedSession = sessionService.getSessionWithSection(result.session().getSessionId())
                    .orElse(result.session());

            Map<String, Object> data = convertSessionToMap(
                    savedSession,
                    sessionService.getAttendanceSummary(savedSession.getSessionId()),
                    LocalDateTime.now()
            );
            data.put("overlapDetected", result.overlapDetected());

            HttpStatus status = result.overlapDetected() ? HttpStatus.CREATED : HttpStatus.CREATED;
            return ResponseEntity.status(status)
                    .body(new ApiResponse(true,
                            result.overlapDetected()
                                    ? "Session created with overlap warning"
                                    : "Session created successfully",
                            data));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error creating session: " + e.getMessage()));
        }
    }

    @PreAuthorize("hasAnyRole('ADMIN','PROFESSOR','TEACHING_ASSISTANT')")
    @GetMapping("/{sessionId}")
    public ResponseEntity<ApiResponse> getSession(@PathVariable Integer sessionId) {
        try {
            User currentUser = getCurrentUser();
            Optional<Session> sessionOpt = sessionService.getSessionWithSection(sessionId);
            if (sessionOpt.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ApiResponse(false, "Session not found"));
            }

            Session session = sessionOpt.get();
            if (!sessionService.canUserAccessSession(currentUser, session)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(new ApiResponse(false, "You are not permitted to view this session"));
            }
            SessionService.AttendanceSummary summary = sessionService.getAttendanceSummary(sessionId);
            Map<String, Object> data = convertSessionToMap(session, summary, LocalDateTime.now());

            return ResponseEntity.ok(new ApiResponse(true, "Session retrieved successfully", data));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error retrieving session: " + e.getMessage()));
        }
    }

    @PreAuthorize("hasAnyRole('ADMIN','PROFESSOR','TEACHING_ASSISTANT')")
    @PostMapping("/{sessionId}/start")
    public ResponseEntity<ApiResponse> startSession(@PathVariable Integer sessionId,
                                                    @RequestBody(required = false) StartStopRequest request) {
        try {
            User currentUser = getCurrentUser();
            if (currentUser.getRole() == User.Role.TEACHING_ASSISTANT) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(new ApiResponse(false, "Teaching assistants cannot start sessions"));
            }
            Session session = sessionService.getSessionWithSection(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("Session not found"));
            if (!sessionService.canUserAccessSession(currentUser, session)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(new ApiResponse(false, "You are not permitted to start this session"));
            }
            sessionService.startSession(sessionId, currentUser.getUserId());
            session = sessionService.getSessionWithSection(sessionId)
                    .orElseThrow(() -> new IllegalStateException("Session not found after start"));
            Map<String, Object> data = convertSessionToMap(
                    session,
                    sessionService.getAttendanceSummary(sessionId),
                    LocalDateTime.now()
            );
            return ResponseEntity.ok(new ApiResponse(true, "Session started successfully", data));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiResponse(false, e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error starting session: " + e.getMessage()));
        }
    }

    @PreAuthorize("hasAnyRole('ADMIN','PROFESSOR','TEACHING_ASSISTANT')")
    @PostMapping("/{sessionId}/close")
    public ResponseEntity<ApiResponse> closeSession(@PathVariable Integer sessionId,
                                                    @RequestBody(required = false) StartStopRequest request) {
        try {
            User currentUser = getCurrentUser();
            if (currentUser.getRole() == User.Role.TEACHING_ASSISTANT) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(new ApiResponse(false, "Teaching assistants cannot close sessions"));
            }
            Session session = sessionService.getSessionWithSection(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("Session not found"));
            if (!sessionService.canUserAccessSession(currentUser, session)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(new ApiResponse(false, "You are not permitted to close this session"));
            }
            sessionService.closeSession(sessionId, currentUser.getUserId());
            session = sessionService.getSessionWithSection(sessionId)
                    .orElseThrow(() -> new IllegalStateException("Session not found after close"));
            Map<String, Object> data = convertSessionToMap(
                    session,
                    sessionService.getAttendanceSummary(sessionId),
                    LocalDateTime.now()
            );
            return ResponseEntity.ok(new ApiResponse(true, "Session closed successfully", data));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiResponse(false, e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error closing session: " + e.getMessage()));
        }
    }

    @PreAuthorize("hasAnyRole('ADMIN','PROFESSOR')")
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<ApiResponse> deleteSession(@PathVariable Integer sessionId) {
        try {
            User currentUser = getCurrentUser();
            Session session = sessionService.getSessionWithSection(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("Session not found"));

            if (currentUser.getRole() == User.Role.PROFESSOR &&
                    !sessionService.canUserAccessSession(currentUser, session)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(new ApiResponse(false, "You are not permitted to delete this session"));
            }
            sessionService.deleteSession(sessionId);
            return ResponseEntity.ok(new ApiResponse(true, "Session deleted successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiResponse(false, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error deleting session: " + e.getMessage()));
        }
    }

    @PreAuthorize("hasAnyRole('ADMIN','PROFESSOR','TEACHING_ASSISTANT')")
    @GetMapping("/{sessionId}/roster")
    public ResponseEntity<ApiResponse> getSessionRoster(@PathVariable Integer sessionId) {
        try {
            User currentUser = getCurrentUser();
            Session session = sessionService.getSessionWithSection(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("Session not found"));
            if (!sessionService.canUserAccessSession(currentUser, session)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(new ApiResponse(false, "You are not permitted to view this roster"));
            }
            List<SessionService.RosterEntryDetail> roster = sessionService.getRosterWithAttendance(sessionId);
            List<Map<String, Object>> data = roster.stream()
                    .map(this::convertRosterEntryToMap)
                    .toList();

            return ResponseEntity.ok(new ApiResponse(true, "Session roster retrieved successfully", data));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error retrieving session roster: " + e.getMessage()));
        }
    }

    @PreAuthorize("hasAnyRole('ADMIN','PROFESSOR','TEACHING_ASSISTANT')")
    @PatchMapping("/{sessionId}/attendance")
    public ResponseEntity<ApiResponse> updateAttendance(@PathVariable Integer sessionId,
                                                        @Valid @RequestBody AttendanceUpdateRequest request) {
        try {
            User currentUser = getCurrentUser();
            Session session = sessionService.getSessionWithSection(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("Session not found"));
            if (!sessionService.canUserAccessSession(currentUser, session)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(new ApiResponse(false, "You are not permitted to update attendance for this session"));
            }
            List<SessionService.AttendanceUpdate> updates = request.getUpdates() != null
                    ? request.getUpdates().stream()
                        .map(update -> new SessionService.AttendanceUpdate(
                                update.getStudentId(),
                                update.getStatus(),
                                update.getNotes()
                        ))
                        .toList()
                    : List.of();

            List<AttendanceRecord> records = sessionService.applyAttendanceUpdates(
                    sessionId,
                    updates,
                    currentUser.getUserId()
            );

            List<Map<String, Object>> data = records.stream()
                    .map(this::convertAttendanceRecordToMap)
                    .toList();

            return ResponseEntity.ok(new ApiResponse(true, "Attendance updated successfully", data));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiResponse(false, e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error updating attendance: " + e.getMessage()));
        }
    }

    @PreAuthorize("hasAnyRole('ADMIN','PROFESSOR','TEACHING_ASSISTANT')")
    @GetMapping("/{sessionId}/export")
    public ResponseEntity<?> exportSession(@PathVariable Integer sessionId,
                                           @RequestParam String format) {
        try {
            User currentUser = getCurrentUser();
            Session session = sessionService.getSessionWithSection(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("Session not found"));
            if (!sessionService.canUserAccessSession(currentUser, session)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(new ApiResponse(false, "You are not permitted to export this session"));
            }
            SessionExportService.ExportFormat exportFormat = SessionExportService.ExportFormat.fromString(format);
            byte[] fileBytes = sessionExportService.export(sessionId, exportFormat);
            String extension = switch (exportFormat) {
                case CSV -> "csv";
                case XLSX -> "xlsx";
                case PDF -> "pdf";
            };

            String contentType = switch (exportFormat) {
                case CSV -> "text/csv";
                case XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                case PDF -> "application/pdf";
            };

            String filename = "session-" + sessionId + "." + extension;
            ByteArrayResource resource = new ByteArrayResource(fileBytes);

            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"");
            headers.setContentLength(fileBytes.length);
            headers.setContentType(MediaType.parseMediaType(contentType));

            return ResponseEntity.ok()
                    .headers(headers)
                    .body(resource);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(new ApiResponse(false, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(false, "Error exporting session: " + e.getMessage()));
        }
    }

    private Map<String, Object> convertSessionToMap(Session session,
                                                    SessionService.AttendanceSummary summary,
                                                    LocalDateTime now) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("sessionId", session.getSessionId());
        map.put("sectionId", session.getSection().getSectionId());
        map.put("sectionCode", session.getSection().getSectionCode());
        map.put("courseId", session.getSection().getCourse().getCourseId());
        map.put("courseCode", session.getSection().getCourse().getCourseCode());
        map.put("courseName", session.getSection().getCourse().getCourseName());
        map.put("sessionDate", session.getSessionDate().toString());
        map.put("startTime", session.getStartTime().format(TIME_FORMATTER));
        map.put("endTime", session.getEndTime().format(TIME_FORMATTER));
        map.put("startDateTime", session.getSessionDate().atTime(session.getStartTime()).format(DATE_TIME_FORMATTER));
        map.put("endDateTime", session.getSessionDate().atTime(session.getEndTime()).format(DATE_TIME_FORMATTER));
        map.put("location", session.getLocation());
        map.put("status", session.getStatus().name());
        map.put("lateThresholdMinutes", session.getLateThresholdMinutes());
        map.put("openedAt", session.getOpenedAt() != null ? session.getOpenedAt().format(DATE_TIME_FORMATTER) : null);
        map.put("closedAt", session.getClosedAt() != null ? session.getClosedAt().format(DATE_TIME_FORMATTER) : null);

        Map<String, Object> attendance = new LinkedHashMap<>();
        attendance.put("rosterCount", summary.rosterCount());
        attendance.put("presentCount", summary.presentCount());
        attendance.put("absentCount", summary.absentCount());
        attendance.put("lateCount", summary.lateCount());
        attendance.put("recordedCount", summary.recordedCount());
        map.put("attendanceSummary", attendance);

        LocalDateTime startDateTime = session.getSessionDate().atTime(session.getStartTime());
        LocalDateTime endDateTime = session.getSessionDate().atTime(session.getEndTime());
        LocalDateTime startWindowOpensAt = startDateTime.minusMinutes(15);

        boolean isFinished = session.getStatus() == Session.SessionStatus.CLOSED || endDateTime.isBefore(now);
        LocalDate today = now.toLocalDate();
        boolean isToday = session.getSessionDate().isEqual(today);
        LocalDate weekStart = today.with(DayOfWeek.MONDAY);
        LocalDate weekEnd = weekStart.plusDays(6);
        boolean isThisWeek = !session.getSessionDate().isBefore(weekStart)
                && !session.getSessionDate().isAfter(weekEnd);

        String bucket = isFinished ? "finished" : (isToday ? "today" : (isThisWeek ? "this-week" : "upcoming"));
        map.put("bucket", bucket);

        boolean startWindowOpen = !now.isBefore(startWindowOpensAt) && now.isBefore(endDateTime);
        boolean canStart = session.getStatus() == Session.SessionStatus.PENDING && startWindowOpen;
        boolean canClose = session.getStatus() == Session.SessionStatus.OPEN;

        map.put("canStart", canStart);
        map.put("canClose", canClose);
        map.put("startWindowOpensAt", startWindowOpensAt.format(DATE_TIME_FORMATTER));
        map.put("startWindowOpen", startWindowOpen);

        return map;
    }

    private Map<String, Object> convertRosterEntryToMap(SessionService.RosterEntryDetail entry) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("rosterId", entry.rosterEntry().getRosterId());
        map.put("sessionId", entry.rosterEntry().getSession().getSessionId());
        map.put("studentId", entry.rosterEntry().getStudent().getStudentId());
        map.put("studentName", entry.rosterEntry().getStudent().getFullName());
        map.put("studentEmail", entry.rosterEntry().getStudent().getEmail());
        map.put("addedAt", entry.rosterEntry().getAddedAt() != null
                ? entry.rosterEntry().getAddedAt().format(DATE_TIME_FORMATTER)
                : null);
        map.put("addedBy", entry.rosterEntry().getAddedBy() != null
                ? entry.rosterEntry().getAddedBy().getFullName()
                : null);

        AttendanceRecord record = entry.attendanceRecord();
        if (record != null) {
            map.put("attendanceStatus", record.getStatus().name());
            map.put("notes", record.getNotes());
            map.put("markedAt", record.getMarkedAt() != null ? record.getMarkedAt().format(DATE_TIME_FORMATTER) : null);
            map.put("markedBy", record.getMarkedBy() != null ? record.getMarkedBy().getFullName() : null);
            map.put("markingMethod", record.getMarkingMethod().name());
        } else {
            map.put("attendanceStatus", null);
            map.put("notes", null);
            map.put("markedAt", null);
            map.put("markedBy", null);
            map.put("markingMethod", null);
        }

        return map;
    }

    private Map<String, Object> convertAttendanceRecordToMap(AttendanceRecord record) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("attendanceId", record.getAttendanceId());
        map.put("sessionId", record.getSession().getSessionId());
        map.put("studentId", record.getStudent().getStudentId());
        map.put("status", record.getStatus().name());
        map.put("notes", record.getNotes());
        map.put("markingMethod", record.getMarkingMethod().name());
        map.put("markedAt", record.getMarkedAt() != null ? record.getMarkedAt().format(DATE_TIME_FORMATTER) : null);
        map.put("markedBy", record.getMarkedBy() != null ? record.getMarkedBy().getFullName() : null);
        return map;
    }

    private LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Session date is required");
        }
        return LocalDate.parse(value.trim());
    }

    private LocalTime parseTime(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Time value is required");
        }
        return LocalTime.parse(value.trim());
    }

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

    public static class SessionCreateRequest {
        private Integer sectionId;
        private String sessionDate;
        private String startTime;
        private String endTime;
        private String location;
        private Integer lateThresholdMinutes;
        private Integer createdByUserId;

        public Integer getSectionId() {
            return sectionId;
        }

        public void setSectionId(Integer sectionId) {
            this.sectionId = sectionId;
        }

        public String getSessionDate() {
            return sessionDate;
        }

        public void setSessionDate(String sessionDate) {
            this.sessionDate = sessionDate;
        }

        public String getStartTime() {
            return startTime;
        }

        public void setStartTime(String startTime) {
            this.startTime = startTime;
        }

        public String getEndTime() {
            return endTime;
        }

        public void setEndTime(String endTime) {
            this.endTime = endTime;
        }

        public String getLocation() {
            return location;
        }

        public void setLocation(String location) {
            this.location = location;
        }

        public Integer getLateThresholdMinutes() {
            return lateThresholdMinutes;
        }

        public void setLateThresholdMinutes(Integer lateThresholdMinutes) {
            this.lateThresholdMinutes = lateThresholdMinutes;
        }

        public Integer getCreatedByUserId() {
            return createdByUserId;
        }

        public void setCreatedByUserId(Integer createdByUserId) {
            this.createdByUserId = createdByUserId;
        }
    }

    public static class StartStopRequest {
        private Integer userId;

        public Integer getUserId() {
            return userId;
        }

        public void setUserId(Integer userId) {
            this.userId = userId;
        }
    }

    public static class AttendanceUpdateRequest {
        private Integer markedByUserId;
        private List<AttendanceUpdateItem> updates;

        public Integer getMarkedByUserId() {
            return markedByUserId;
        }

        public void setMarkedByUserId(Integer markedByUserId) {
            this.markedByUserId = markedByUserId;
        }

        public List<AttendanceUpdateItem> getUpdates() {
            return updates;
        }

        public void setUpdates(List<AttendanceUpdateItem> updates) {
            this.updates = updates;
        }
    }

    public static class AttendanceUpdateItem {
        private String studentId;
        private AttendanceRecord.AttendanceStatus status;
        private String notes;

        public String getStudentId() {
            return studentId;
        }

        public void setStudentId(String studentId) {
            this.studentId = studentId;
        }

        public AttendanceRecord.AttendanceStatus getStatus() {
            return status;
        }

        public void setStatus(AttendanceRecord.AttendanceStatus status) {
            this.status = status;
        }

        public String getNotes() {
            return notes;
        }

        public void setNotes(String notes) {
            this.notes = notes;
        }
    }

    private User getCurrentUser() {
        UserPrincipal principal = SecurityUtils.getCurrentUserPrincipal()
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found"));
        return userService.getUserById(principal.getUser().getUserId())
                .orElse(principal.getUser());
    }
}
