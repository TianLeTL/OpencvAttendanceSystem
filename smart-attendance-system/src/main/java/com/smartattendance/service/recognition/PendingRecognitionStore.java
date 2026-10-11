package com.smartattendance.service.recognition;

import com.smartattendance.service.recognition.FaceRecognitionService.BoundingBox;
import com.smartattendance.service.recognition.FaceRecognitionService.MatchCandidate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory registry of recognition results that require human confirmation.
 * Entries expire automatically to prevent unbounded growth.
 */
@Component
public class PendingRecognitionStore {

    private static final Duration EXPIRY = Duration.ofMinutes(5);

    private final Map<String, PendingRecognition> pending = new ConcurrentHashMap<>();

    public boolean hasPendingForStudent(Integer sessionId, String studentId) {
        if (sessionId == null || studentId == null) {
            return false;
        }
        cleanup();
        return findBySessionAndStudent(sessionId, studentId).isPresent();
    }

    public Optional<PendingRecognition> findBySessionAndStudent(Integer sessionId, String studentId) {
        if (sessionId == null || studentId == null) {
            return Optional.empty();
        }
        cleanup();
        return pending.values().stream()
                .filter(entry -> sessionId.equals(entry.sessionId())
                        && entry.candidate() != null
                        && studentId.equals(entry.candidate().studentId()))
                .findFirst();
    }

    public PendingRecognition create(Integer sessionId,
                                     MatchCandidate candidate,
                                     List<MatchCandidate> alternatives,
                                     BoundingBox boundingBox,
                                     int frameWidth,
                                     int frameHeight,
                                     double detectionScore) {
        cleanup();
        String token = UUID.randomUUID().toString();
        PendingRecognition recognition = new PendingRecognition(
                token,
                sessionId,
                candidate,
                alternatives != null ? new ArrayList<>(alternatives) : List.of(),
                boundingBox,
                frameWidth,
                frameHeight,
                detectionScore,
                Instant.now()
        );
        pending.put(token, recognition);
        return recognition;
    }

    public PendingRecognition refresh(String token,
                                      Integer sessionId,
                                      MatchCandidate candidate,
                                      List<MatchCandidate> alternatives,
                                      BoundingBox boundingBox,
                                      int frameWidth,
                                      int frameHeight,
                                      double detectionScore) {
        cleanup();
        if (token == null) {
            throw new IllegalArgumentException("Pending token cannot be null");
        }
        pending.computeIfPresent(token, (key, existing) -> new PendingRecognition(
                key,
                sessionId != null ? sessionId : existing.sessionId(),
                candidate != null ? candidate : existing.candidate(),
                alternatives != null ? new ArrayList<>(alternatives) : existing.alternatives(),
                boundingBox != null ? boundingBox : existing.boundingBox(),
                frameWidth > 0 ? frameWidth : existing.frameWidth(),
                frameHeight > 0 ? frameHeight : existing.frameHeight(),
                detectionScore > 0 ? detectionScore : existing.detectionScore(),
                Instant.now()
        ));
        PendingRecognition updated = pending.get(token);
        if (updated == null) {
            updated = new PendingRecognition(
                    token,
                    sessionId,
                    candidate,
                    alternatives != null ? new ArrayList<>(alternatives) : List.of(),
                    boundingBox,
                    frameWidth,
                    frameHeight,
                    detectionScore,
                    Instant.now()
            );
            pending.put(token, updated);
        }
        return updated;
    }

    public Optional<PendingRecognition> consume(String token) {
        if (token == null) {
            return Optional.empty();
        }
        cleanup();
        return Optional.ofNullable(pending.remove(token));
    }

    public void discard(String token) {
        if (token == null) {
            return;
        }
        pending.remove(token);
    }

    private void cleanup() {
        Instant threshold = Instant.now().minus(EXPIRY);
        pending.entrySet().removeIf(entry -> entry.getValue().createdAt().isBefore(threshold));
    }

    public record PendingRecognition(
            String token,
            Integer sessionId,
            MatchCandidate candidate,
            List<MatchCandidate> alternatives,
            BoundingBox boundingBox,
            int frameWidth,
            int frameHeight,
            double detectionScore,
            Instant createdAt
    ) {
        public List<MatchCandidate> alternatives() {
            return alternatives == null ? List.of() : Collections.unmodifiableList(alternatives);
        }
    }
}
