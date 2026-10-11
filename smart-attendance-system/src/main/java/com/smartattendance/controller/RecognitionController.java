package com.smartattendance.controller;

import com.smartattendance.entity.Session;
import com.smartattendance.entity.Session.SessionStatus;
import com.smartattendance.entity.User;
import com.smartattendance.security.SecurityUtils;
import com.smartattendance.service.SessionService;
import com.smartattendance.service.recognition.AutomaticAttendanceService;
import com.smartattendance.service.recognition.FaceRecognitionService;
import com.smartattendance.service.recognition.RecognitionEventService;
import com.smartattendance.service.UserService;
import com.smartattendance.service.auth.UserPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@Validated
@CrossOrigin(origins = "*")
@RequestMapping("/api/recognition")
public class RecognitionController {

    private final SessionService sessionService;
    private final FaceRecognitionService faceRecognitionService;
    private final AutomaticAttendanceService automaticAttendanceService;
    private final RecognitionEventService recognitionEventService;
    private final UserService userService;

    public RecognitionController(SessionService sessionService,
                                 FaceRecognitionService faceRecognitionService,
                                 AutomaticAttendanceService automaticAttendanceService,
                                 RecognitionEventService recognitionEventService,
                                 UserService userService) {
        this.sessionService = sessionService;
        this.faceRecognitionService = faceRecognitionService;
        this.automaticAttendanceService = automaticAttendanceService;
        this.recognitionEventService = recognitionEventService;
        this.userService = userService;
    }

    @GetMapping("/events")
    public SseEmitter streamEvents(@RequestParam Integer sessionId) {
        return recognitionEventService.subscribe(sessionId);
    }

    @GetMapping("/sessions/open")
    public ResponseEntity<ApiResponse> listOpenSessions() {
        Optional<User> currentUser = resolveCurrentUser();
        if (currentUser.isEmpty()) {
            return unauthorizedResponse();
        }

        List<Session> openSessions = sessionService.getSessionsVisibleTo(currentUser.get()).stream()
                .filter(session -> session.getStatus() == SessionStatus.OPEN)
                .toList();

        List<Map<String, Object>> data = openSessions.stream()
                .map(session -> {
                    Map<String, Object> map = new java.util.LinkedHashMap<>();
                    map.put("sessionId", session.getSessionId());
                    map.put("courseCode", session.getSection().getCourse().getCourseCode());
                    map.put("courseName", session.getSection().getCourse().getCourseName());
                    map.put("sectionCode", session.getSection().getSectionCode());
                    map.put("sessionDate", session.getSessionDate().toString());
                    map.put("startTime", session.getStartTime().toString());
                    map.put("endTime", session.getEndTime().toString());
                    map.put("lateThresholdMinutes", session.getLateThresholdMinutes());
                    return map;
                })
                .toList();

        return ResponseEntity.ok(new ApiResponse(true, "Open sessions retrieved", data));
    }

    @PostMapping("/start")
    public ResponseEntity<ApiResponse> startScanner(@Valid @RequestBody SessionCommand request) {
        Optional<Session> optionalSession = sessionService.getSessionWithSection(request.sessionId());
        if (optionalSession.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiResponse(false, "Session not found"));
        }

        Optional<User> currentUser = resolveCurrentUser();
        if (currentUser.isEmpty()) {
            return unauthorizedResponse();
        }

        Session session = optionalSession.get();
        if (session.getStatus() != SessionStatus.OPEN) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ApiResponse(false, "Session must be OPEN before scanning can begin"));
        }

        if (!sessionService.canUserAccessSession(currentUser.get(), session)) {
            return forbiddenResponse();
        }

        automaticAttendanceService.clearSessionCaches(session.getSessionId());
        recognitionEventService.publish(session.getSessionId(), "recognition.ready", Map.of(
                "timestamp", LocalDateTime.now().toString(),
                "profilesLoaded", faceRecognitionService.getCachedProfileCount()
        ));

        return ResponseEntity.ok(new ApiResponse(true, "Scanner ready",
                Map.of(
                        "sessionId", session.getSessionId(),
                        "courseCode", session.getSection().getCourse().getCourseCode(),
                        "sectionCode", session.getSection().getSectionCode()
                )));
    }

    @PostMapping("/stop")
    public ResponseEntity<ApiResponse> stopScanner(@Valid @RequestBody SessionCommand request) {
        Optional<User> currentUser = resolveCurrentUser();
        if (currentUser.isEmpty()) {
            return unauthorizedResponse();
        }

        Optional<Session> optionalSession = sessionService.getSessionWithSection(request.sessionId());
        if (optionalSession.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiResponse(false, "Session not found"));
        }

        if (!sessionService.canUserAccessSession(currentUser.get(), optionalSession.get())) {
            return forbiddenResponse();
        }

        automaticAttendanceService.clearSessionCaches(request.sessionId());
        recognitionEventService.closeSession(request.sessionId());
        return ResponseEntity.ok(new ApiResponse(true, "Scanner stopped"));
    }

    @PostMapping("/frame")
    public ResponseEntity<ApiResponse> submitFrame(@Valid @RequestBody FramePayload payload) {
        Optional<Session> optionalSession = sessionService.getSessionWithSection(payload.sessionId());
        if (optionalSession.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiResponse(false, "Session not found"));
        }

        Optional<User> currentUser = resolveCurrentUser();
        if (currentUser.isEmpty()) {
            return unauthorizedResponse();
        }

        Session session = optionalSession.get();
        if (session.getStatus() != SessionStatus.OPEN) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ApiResponse(false, "Session must be OPEN"));
        }

        if (!sessionService.canUserAccessSession(currentUser.get(), session)) {
            return forbiddenResponse();
        }

        if (faceRecognitionService.getCachedProfileCount() == 0) {
            recognitionEventService.publish(session.getSessionId(), "recognition.no-training-data", Map.of(
                    "timestamp", LocalDateTime.now().toString()
            ));
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new ApiResponse(true, "No training data available yet"));
        }

        var frameAnalysis = faceRecognitionService.analyseFrame(payload.imageData());
        automaticAttendanceService.applyFrame(session.getSessionId(), frameAnalysis);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new ApiResponse(true, "Frame processed", Map.of(
                        "facesDetected", frameAnalysis.faces().size()
                )));
    }

    @PostMapping("/review/{token}")
    public ResponseEntity<ApiResponse> resolveReview(@PathVariable String token,
                                                     @Valid @RequestBody ReviewAction action) {
        try {
            if (action.accepted()) {
                automaticAttendanceService.confirmReview(token, true, action.markedByUserId());
                return ResponseEntity.ok(new ApiResponse(true, "Attendance confirmed from review"));
            } else {
                automaticAttendanceService.confirmReview(token, false, action.markedByUserId());
                return ResponseEntity.ok(new ApiResponse(true, "Review rejected"));
            }
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ApiResponse(false, ex.getMessage()));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(new ApiResponse(false, ex.getMessage()));
        }
    }

    public record SessionCommand(
            @NotNull(message = "Session ID is required")
            Integer sessionId
    ) {}

    public record FramePayload(
            @NotNull(message = "Session ID is required")
            Integer sessionId,

            @NotBlank(message = "Frame payload is required")
            String imageData
    ) {}

    public record ReviewAction(
            boolean accepted,
            Integer markedByUserId
    ) {}

    public record ApiResponse(boolean success, String message, Object data) {
        public ApiResponse(boolean success, String message) {
            this(success, message, null);
        }
    }

    private Optional<User> resolveCurrentUser() {
        return SecurityUtils.getCurrentUserPrincipal()
                .map(UserPrincipal::getUser)
                .map(user -> userService.getUserById(user.getUserId()).orElse(user));
    }

    private ResponseEntity<ApiResponse> unauthorizedResponse() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiResponse(false, "Unauthorized"));
    }

    private ResponseEntity<ApiResponse> forbiddenResponse() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiResponse(false, "You are not allowed to access this session"));
    }
}
